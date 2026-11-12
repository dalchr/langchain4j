CREATE TABLE IF NOT EXISTS CITIES (
    ID SERIAL PRIMARY KEY,
    NAME VARCHAR(255) NOT NULL,
    POPULATION INT NOT NULL
);

-- Pre-populate with sample cities for demonstration
INSERT INTO CITIES (NAME, POPULATION) VALUES
    ('Moscow', 12691000),
    ('London', 8982000),
    ('New York', 8336000),
    ('Tokyo', 13960000),
    ('Paris', 2161000)
ON CONFLICT DO NOTHING;
