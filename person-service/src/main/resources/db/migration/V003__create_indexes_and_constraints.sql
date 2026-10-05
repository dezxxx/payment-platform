ALTER TABLE person.countries ADD CONSTRAINT uk_countries_alpha2 UNIQUE (alpha2);
ALTER TABLE person.countries ADD CONSTRAINT uk_countries_alpha3 UNIQUE (alpha3);

ALTER TABLE person.addresses
    ADD CONSTRAINT fk_addresses_country FOREIGN KEY (country_id) REFERENCES person.countries (id);

-- one address row per user: two users may live at the same address, each with a row of their own
ALTER TABLE person.users ADD CONSTRAINT uk_users_address_id UNIQUE (address_id);
ALTER TABLE person.users
    ADD CONSTRAINT fk_users_address FOREIGN KEY (address_id) REFERENCES person.addresses (id);

-- one individual per user
ALTER TABLE person.individuals ADD CONSTRAINT uk_individuals_user_id UNIQUE (user_id);
ALTER TABLE person.individuals
    ADD CONSTRAINT fk_individuals_user FOREIGN KEY (user_id) REFERENCES person.users (id);

-- email is unique regardless of case: Ivan@x.org and ivan@x.org are one user
CREATE UNIQUE INDEX uk_users_email_lower ON person.users (lower(email));

CREATE INDEX idx_addresses_country_id ON person.addresses (country_id);
