CREATE TABLE currencies (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(3) NOT NULL,
    iso_code CHAR(3) NOT NULL,
    description VARCHAR(64) NOT NULL,
    symbol VARCHAR(8),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    modified_at TIMESTAMP,
    CONSTRAINT uk_currencies_code UNIQUE (code),
    CONSTRAINT uk_currencies_iso_code UNIQUE (iso_code),
    CONSTRAINT ck_currencies_code CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_currencies_iso_code CHECK (iso_code ~ '^[0-9]{3}$')
);

CREATE TABLE rate_providers (
    provider_code VARCHAR(3) PRIMARY KEY,
    provider_name VARCHAR(28) NOT NULL,
    description VARCHAR(255),
    priority INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    modified_at TIMESTAMP,
    CONSTRAINT uk_rate_providers_provider_name UNIQUE (provider_name),
    CONSTRAINT uk_rate_providers_priority UNIQUE (priority)
);

CREATE TABLE conversion_rates (
    id BIGSERIAL PRIMARY KEY,
    source_code VARCHAR(3) NOT NULL REFERENCES currencies (code),
    destination_code VARCHAR(3) NOT NULL REFERENCES currencies (code),
    rate NUMERIC(19, 8) NOT NULL,
    rate_begin_time TIMESTAMP NOT NULL DEFAULT now(),
    rate_end_time TIMESTAMP NOT NULL,
    provider_code VARCHAR(3) NOT NULL REFERENCES rate_providers (provider_code),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_conversion_rates_pair_begin UNIQUE (source_code, destination_code, rate_begin_time),
    CONSTRAINT ck_conversion_rates_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_conversion_rates_pair_differs CHECK (source_code <> destination_code),
    CONSTRAINT ck_conversion_rates_end_after_begin CHECK (rate_end_time > rate_begin_time)
);

CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
