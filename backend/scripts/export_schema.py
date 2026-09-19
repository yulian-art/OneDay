"""Generate the executable SQLite baseline and PostgreSQL review artifact from Alembic."""

from io import StringIO
from pathlib import Path

from alembic import command
from alembic.config import Config

root = Path(__file__).resolve().parents[1]
for url, output in [
    ("sqlite://", root.parent / "DB_SCHEMA.sql"),
    ("postgresql+psycopg://", root / "docs/schema-postgresql.sql"),
]:
    stream = StringIO()
    config = Config(str(root / "alembic.ini"), output_buffer=stream)
    config.attributes["database_url"] = url
    command.upgrade(config, "head", sql=True)
    header = (
        "-- OneDay backend 0.1.0: generated from Alembic revision 0001_backend.\n"
        "-- Empty databases only. Existing databases: run alembic upgrade head.\n"
        "-- Historical mobile/cloud design: backend/docs/DB_SCHEMA_DESIGN_ARCHIVE.txt\n"
    )
    if url.startswith("sqlite"):
        header += "PRAGMA foreign_keys=ON;\nBEGIN;\n"
    output.write_text(
        header + stream.getvalue() + ("\nCOMMIT;\n" if url.startswith("sqlite") else ""), encoding="utf-8"
    )
    print(output.name)
