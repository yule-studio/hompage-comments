-- serial is the issue number stamped onto the file that goes out, so it has to
-- keep counting even after rows are deleted: autoincrement, never reused.
create table if not exists resume_download (
    serial     integer primary key autoincrement,
    name       text not null,
    email      text not null,
    org        text,
    purpose    text,
    ip         text,
    user_agent text,
    created_at text not null
);

create index if not exists idx_resume_download_created_at
    on resume_download (created_at desc);
