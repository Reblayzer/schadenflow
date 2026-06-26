CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(30) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
