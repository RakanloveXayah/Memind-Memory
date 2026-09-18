-- Memind 数据库初始化（需超级用户执行，因为 pgvector 不是 trusted extension）
--
--   psql -U postgres -h 127.0.0.1 -p 5432 -d postgres -v password='你的密码' -f setup.sql
--
-- 脚本可重复执行：角色与库已存在时不会报错。只创建独立的角色与库，不触碰实例上已有的任何数据。
-- 表结构、向量列维度与近邻索引、RLS 策略由应用启动时执行 schema.sql + PgVectorInitializer 完成。

SELECT format('CREATE ROLE memind LOGIN PASSWORD %L', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'memind')\gexec
ALTER ROLE memind LOGIN PASSWORD :'password';

SELECT 'CREATE DATABASE memind OWNER memind'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'memind')\gexec
SELECT 'CREATE DATABASE memind_test OWNER memind'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'memind_test')\gexec

\connect memind
CREATE EXTENSION IF NOT EXISTS vector;

\connect memind_test
CREATE EXTENSION IF NOT EXISTS vector;