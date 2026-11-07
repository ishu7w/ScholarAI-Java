package app.scholarai;

import static app.scholarai.Accounts.require;

import java.io.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * Encapsulation: private document bytes are stored in PostgreSQL, never exposed as public files.
 * Composition: document checks and persistence are separate collaborators, not subclasses.
 */
public class Documents {
  private final Store store;
  private final DocumentChecks checks;

  public Documents(Store store, DocumentChecks checks) {
    this.store = store;
    this.checks = checks;
  }

  @Transactional
  public void upload(String user, String kind, MultipartFile file) throws IOException {
    require(
        Set.of("resume", "income_cert", "marksheet", "id").contains(kind),
        "Choose a valid document kind.");
    require(!file.isEmpty() && file.getSize() <= 4 * 1024 * 1024, "Upload a file up to 4 MB.");
    // Serialize one account's uploads so concurrent requests cannot exceed its storage quota.
    var person = store.one("SELECT name FROM users WHERE id=? FOR UPDATE", user);
    require(person != null, "Account not found.");
    require(
        store.rows("SELECT id FROM documents WHERE owner_id=?", user).size() < 30,
        "Document limit reached. Remove an older document first.");
    byte[] bytes = file.getBytes();
    String media;
    if (bytes.length >= 5
        && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
      media = "application/pdf";
    else if (bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      media = "image/png";
    else if (bytes.length >= 3
        && bytes[0] == (byte) 255
        && bytes[1] == (byte) 216
        && bytes[2] == (byte) 255) media = "image/jpeg";
    else
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Only PDF, PNG and JPEG files are accepted.");
    String id = UUID.randomUUID().toString(),
        name =
            Optional.ofNullable(file.getOriginalFilename())
                .orElse("document")
                .replaceAll("[^a-zA-Z0-9 ._-]", "_");
    if (name.length() > 200) name = name.substring(name.length() - 200);
    var result =
        checks.check(kind, media, bytes, store.profile(user), person.get("name").toString());
    {
      store
          .jdbc()
          .update(
              "INSERT INTO documents(id,owner_id,name,media_type,size_bytes,kind,status,checks)"
                  + " VALUES (?,?,?,?,?,?,?,?)",
              id,
              user,
              name,
              media,
              bytes.length,
              kind,
              result.status(),
              store.json(result));
      store
          .jdbc()
          .update("INSERT INTO document_contents(document_id,content) VALUES (?,?)", id, bytes);
      store.audit(user, "document.uploaded", id);
    }
  }

  public Map<String, Object> owned(String user, String id) {
    var row = store.one("SELECT * FROM documents WHERE id=? AND owner_id=?", id, user);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
    return row;
  }

  public Map<String, Object> accessible(Map<String, Object> user, String id) {
    var owned = store.one("SELECT * FROM documents WHERE id=? AND owner_id=?", id, user.get("id"));
    if (owned != null) return owned;
    if ("institution".equals(user.get("role")) && Boolean.TRUE.equals(user.get("approved"))) {
      var shared =
          store.one(
              "SELECT d.* FROM documents d JOIN application_documents ad ON ad.document_id=d.id"
                  + " JOIN applications a ON a.id=ad.application_id JOIN scholarships s ON"
                  + " s.id=a.scholarship_id WHERE d.id=? AND s.owner_id=?",
              id,
              user.get("id"));
      if (shared != null) return shared;
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
  }

  public byte[] content(String id) {
    byte[] data =
        store
            .jdbc()
            .query(
                "SELECT content FROM document_contents WHERE document_id=?",
                rs -> rs.next() ? rs.getBytes(1) : null,
                id);
    if (data == null)
      throw new ResponseStatusException(
          HttpStatus.GONE, "This legacy file has not been migrated to durable storage.");
    return data;
  }

  @Transactional
  public void delete(String user, String id) {
    owned(user, id);
    store.jdbc().update("DELETE FROM documents WHERE id=? AND owner_id=?", id, user);
    store.audit(user, "document.deleted", id);
  }
}
