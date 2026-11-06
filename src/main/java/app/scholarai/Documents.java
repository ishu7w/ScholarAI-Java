package app.scholarai;

import static app.scholarai.Accounts.require;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * Encapsulation: private filesystem paths never come from user filenames or public URLs.
 * Composition: document checks and persistence are separate collaborators, not subclasses.
 */
public class Documents {
  private final Store store;
  private final Path root;
  private final DocumentChecks checks;

  public Documents(Store store, DocumentChecks checks, @Value("${app.storage}") String root)
      throws IOException {
    this.store = store;
    this.checks = checks;
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
  }

  @Transactional
  public void upload(String user, String kind, MultipartFile file) throws IOException {
    require(
        Set.of("resume", "income_cert", "marksheet", "id").contains(kind),
        "Choose a valid document kind.");
    require(!file.isEmpty() && file.getSize() <= 5 * 1024 * 1024, "Upload a file up to 5 MB.");
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
    Path destination = root.resolve(id);
    var result =
        checks.check(kind, media, bytes, store.profile(user), person.get("name").toString());
    try {
      Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
    } catch (IOException ex) {
      cleanup(destination);
      throw ex;
    }
    // The filesystem is not transactional: remove new bytes if the surrounding DB transaction rolls
    // back.
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) cleanup(destination);
            }
          });
    try {
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
      store.audit(user, "document.uploaded", id);
    } catch (RuntimeException ex) {
      Files.deleteIfExists(destination);
      throw ex;
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

  public Path path(String id) {
    return root.resolve(UUID.fromString(id).toString());
  }

  @Transactional
  public void delete(String user, String id) throws IOException {
    owned(user, id);
    Path file = path(id);
    store.jdbc().update("DELETE FROM documents WHERE id=? AND owner_id=?", id, user);
    store.audit(user, "document.deleted", id);
    // Keep the original bytes if a failed audit/database commit restores the document row.
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cleanup(file);
            }
          });
    else Files.deleteIfExists(file);
  }

  private void cleanup(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ex) {
      org.slf4j.LoggerFactory.getLogger(Documents.class)
          .warn("Private document cleanup failed; reconcile document storage.");
    }
  }
}
