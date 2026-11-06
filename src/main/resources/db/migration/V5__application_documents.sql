CREATE TABLE application_documents (application_id VARCHAR(36) NOT NULL REFERENCES applications(id) ON DELETE CASCADE, document_id VARCHAR(36) NOT NULL REFERENCES documents(id) ON DELETE CASCADE, PRIMARY KEY(application_id, document_id));
CREATE INDEX application_documents_document_idx ON application_documents(document_id);
