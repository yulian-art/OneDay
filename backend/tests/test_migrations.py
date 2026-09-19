import sqlite3
from io import StringIO
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from conftest import migrate
from sqlalchemy import inspect

from oneday.config import Settings
from oneday.db import database
from oneday.models import Base


def test_migration_upgrade_downgrade_and_schema(tmp_path):
    path = tmp_path / "migration.db"
    url = f"sqlite:///{path}"
    config = migrate(url)
    command.check(config)
    command.upgrade(config, "head")
    engine, _ = database(url)
    assert set(inspect(engine).get_table_names()) == set(Base.metadata.tables) | {"alembic_version"}
    engine.dispose()
    command.downgrade(config, "base")
    command.upgrade(config, "head")
    with sqlite3.connect(path) as conn:
        assert conn.execute("PRAGMA foreign_key_check").fetchall() == []


def test_postgres_offline_migration_compiles():
    stream = StringIO()
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"), output_buffer=stream)
    config.attributes["database_url"] = "postgresql+psycopg://test:test@localhost/oneday"
    command.upgrade(config, "head", sql=True)
    ddl = stream.getvalue()
    assert "CREATE TABLE candidates" in ddl
    assert "FOREIGN KEY(task_id, task_version)" in ddl


def test_production_rejects_dev_auth():
    with pytest.raises(ValueError):
        Settings(environment="production", _env_file=None)
