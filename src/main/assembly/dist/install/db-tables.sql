CREATE TABLE IF NOT EXISTS basic_file_metadata (
    storage_identifier VARCHAR(60) NOT NULL,
    dataset_doi VARCHAR(100) NOT NULL,
    version_sequence_number INTEGER NOT NULL,
    file_name VARCHAR(1000) NOT NULL,
    directory_label VARCHAR(1000),
    mime_type VARCHAR(255) NOT NULL,
    sha1_checksum CHAR(40) NOT NULL,
    PRIMARY KEY (storage_identifier, dataset_doi, version_sequence_number));

GRANT INSERT, SELECT, UPDATE, DELETE ON basic_file_metadata TO dd_migration_info;
