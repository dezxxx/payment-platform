-- Envers history lives apart from the working data
CREATE SCHEMA IF NOT EXISTS person_history;

-- one row per transaction that changed audited data
CREATE TABLE person_history.revinfo (
    rev BIGSERIAL PRIMARY KEY,
    revtstmp BIGINT NOT NULL
);

-- revision_type: 0 = created, 1 = changed, 2 = deleted.
-- Data columns are nullable: they copy the entity as it was at that revision.
-- Every *_mod column says whether that field changed in the revision (withModifiedFlag).
-- version is not copied: Envers skips the optimistic locking field.

CREATE TABLE person_history.addresses_history (
    id UUID NOT NULL,
    revision BIGINT NOT NULL,
    revision_type SMALLINT NOT NULL,
    created TIMESTAMP,
    created_mod BOOLEAN,
    updated TIMESTAMP,
    updated_mod BOOLEAN,
    country_id INTEGER,
    country_id_mod BOOLEAN,
    address VARCHAR(128),
    address_mod BOOLEAN,
    zip_code VARCHAR(32),
    zip_code_mod BOOLEAN,
    archived TIMESTAMP,
    archived_mod BOOLEAN,
    city VARCHAR(64),
    city_mod BOOLEAN,
    state VARCHAR(64),
    state_mod BOOLEAN,
    CONSTRAINT pk_addresses_history PRIMARY KEY (id, revision),
    CONSTRAINT fk_addresses_history_revision FOREIGN KEY (revision) REFERENCES person_history.revinfo (rev)
);

CREATE TABLE person_history.users_history (
    id UUID NOT NULL,
    revision BIGINT NOT NULL,
    revision_type SMALLINT NOT NULL,
    email VARCHAR(1024),
    email_mod BOOLEAN,
    created TIMESTAMP,
    created_mod BOOLEAN,
    updated TIMESTAMP,
    updated_mod BOOLEAN,
    first_name VARCHAR(64),
    first_name_mod BOOLEAN,
    last_name VARCHAR(64),
    last_name_mod BOOLEAN,
    filled BOOLEAN,
    filled_mod BOOLEAN,
    address_id UUID,
    address_id_mod BOOLEAN,
    -- no individual column here (individuals.user_id holds the link), only the flag
    individual_mod BOOLEAN,
    CONSTRAINT pk_users_history PRIMARY KEY (id, revision),
    CONSTRAINT fk_users_history_revision FOREIGN KEY (revision) REFERENCES person_history.revinfo (rev)
);

CREATE TABLE person_history.individuals_history (
    id UUID NOT NULL,
    revision BIGINT NOT NULL,
    revision_type SMALLINT NOT NULL,
    user_id UUID,
    user_id_mod BOOLEAN,
    passport_number VARCHAR(32),
    passport_number_mod BOOLEAN,
    phone_number VARCHAR(32),
    phone_number_mod BOOLEAN,
    verified_at TIMESTAMP,
    verified_at_mod BOOLEAN,
    archived_at TIMESTAMP,
    archived_at_mod BOOLEAN,
    status VARCHAR(32),
    status_mod BOOLEAN,
    CONSTRAINT pk_individuals_history PRIMARY KEY (id, revision),
    CONSTRAINT fk_individuals_history_revision FOREIGN KEY (revision) REFERENCES person_history.revinfo (rev)
);

-- "what changed in revision N" reads by revision; the primary key leads with id and cannot serve it
CREATE INDEX idx_addresses_history_revision ON person_history.addresses_history (revision);
CREATE INDEX idx_users_history_revision ON person_history.users_history (revision);
CREATE INDEX idx_individuals_history_revision ON person_history.individuals_history (revision);
