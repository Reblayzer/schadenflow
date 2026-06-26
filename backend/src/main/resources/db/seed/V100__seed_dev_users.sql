-- Synthetic dev-only users. Password for all three is "password123". Do NOT use in production.
INSERT INTO users (id, username, password_hash, role, created_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'anspruchsteller', '$2b$10$SsjB9marB7zH1nvbtgHxIe4K4s3vR0i/4uOrre40h2bpT3LCII.fu', 'ANSPRUCHSTELLER', now()),
  ('22222222-2222-2222-2222-222222222222', 'sachbearbeiter',  '$2b$10$rviKePhD84CmHicHs3rADO3OiaOJSGfVBuHv0zNdEvFgiCLKCu8A2', 'SACHBEARBEITER',  now()),
  ('33333333-3333-3333-3333-333333333333', 'admin',           '$2b$10$0K4h/oPmfVtQM4X2baKN5OmsY8pJVpnRpL7F4XgYt.risb9QZsmRS', 'ADMIN',           now());
