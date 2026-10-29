-- Synthetic baseline for the isolated Docker database only.
CREATE TABLE IF NOT EXISTS public.users (
    id bigint PRIMARY KEY,
    name varchar(80)
);
INSERT INTO public.users(id, name) VALUES (1, 'Ada') ON CONFLICT (id) DO NOTHING;
