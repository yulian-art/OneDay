from alembic import context
from sqlalchemy import create_engine, pool

from oneday.config import Settings
from oneday.models import Base

config = context.config
url = config.attributes.get("database_url") or Settings().database_url

if context.is_offline_mode():
    context.configure(
        url=url, target_metadata=Base.metadata, literal_binds=True, dialect_opts={"paramstyle": "named"}
    )
    with context.begin_transaction():
        context.run_migrations()
else:
    engine = create_engine(url, poolclass=pool.NullPool)
    with engine.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=Base.metadata,
            render_as_batch=url.startswith("sqlite"),
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()
