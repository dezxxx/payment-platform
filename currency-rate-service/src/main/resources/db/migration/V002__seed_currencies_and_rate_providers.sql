INSERT INTO currencies (code, iso_code, description, symbol) VALUES
    ('USD', '840', 'US Dollar', '$'),
    ('EUR', '978', 'Euro', '€'),
    ('UAH', '980', 'Ukrainian Hryvnia', '₴'),
    ('ILS', '376', 'Israeli New Shekel', '₪');

INSERT INTO rate_providers (provider_code, provider_name, description, priority) VALUES
    ('OXR', 'Open Exchange Rates', 'Hourly rates, USD base', 1);
