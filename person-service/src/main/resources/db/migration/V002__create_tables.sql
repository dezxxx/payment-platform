CREATE TABLE person.countries (
    id SERIAL PRIMARY KEY,
    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 64, not the handout's 32: seven ISO names in V004 are longer than 32
    name VARCHAR(64) NOT NULL,
    alpha2 VARCHAR(2) NOT NULL,
    alpha3 VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE person.addresses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created TIMESTAMP NOT NULL,
    updated TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    country_id INTEGER,
    address VARCHAR(128) NOT NULL,
    zip_code VARCHAR(32),
    archived TIMESTAMP NULL,
    city VARCHAR(64) NOT NULL,
    state VARCHAR(64)
);

CREATE TABLE person.users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    secret_key VARCHAR(64),
    email VARCHAR(1024) NOT NULL,
    created TIMESTAMP NOT NULL,
    updated TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    first_name VARCHAR(64) NOT NULL,
    last_name VARCHAR(64) NOT NULL,
    filled BOOLEAN NOT NULL DEFAULT FALSE,
    address_id UUID
);

CREATE TABLE person.individuals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    passport_number VARCHAR(32),
    phone_number VARCHAR(32),
    verified_at TIMESTAMP NULL,
    archived_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL
);
