-- Initial schema of the domain user.
--
-- Versioned migration: once applied in a persistent environment it is never
-- rewritten. Any change lands as a new migration on top of this one.
--
-- verified_at and archived_at are nullable on purpose: they mark that an event
-- happened, so they must stay empty until it does.

CREATE SCHEMA IF NOT EXISTS person;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE person.countries (
    id SERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    name VARCHAR(64) NOT NULL,
    alpha2 CHAR(2) NOT NULL UNIQUE,
    alpha3 CHAR(3) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL
);

CREATE TABLE person.addresses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    country_id INTEGER REFERENCES person.countries(id),
    address_line VARCHAR(128) NOT NULL,
    zip_code VARCHAR(32),
    city VARCHAR(64) NOT NULL,
    state VARCHAR(64),
    archived_at TIMESTAMPTZ NULL
);

CREATE TABLE person.users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(1024) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_name VARCHAR(64) NOT NULL,
    last_name VARCHAR(64) NOT NULL,
    filled BOOLEAN NOT NULL DEFAULT FALSE,
    address_id UUID REFERENCES person.addresses(id),
    keycloak_user_id VARCHAR(64) UNIQUE NULL
);

CREATE TABLE person.individuals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES person.users(id) ON DELETE CASCADE,
    passport_number VARCHAR(32),
    phone_number VARCHAR(32),
    verified_at TIMESTAMPTZ NULL,
    archived_at TIMESTAMPTZ NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW'
);

CREATE INDEX idx_person_users_email ON person.users(email);
CREATE INDEX idx_person_individuals_phone_number ON person.individuals(phone_number);
