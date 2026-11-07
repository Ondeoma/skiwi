#!/bin/sh
# クラスタ初回作成時にプライマリで1度だけ実行される: アプリ用ロール・DB・PGroonga拡張を作る
set -e
psql -h /tmp -U postgres -v ON_ERROR_STOP=1 <<SQL
create role skiwi login password '${SKIWI_DB_PASSWORD:-skiwi}';
create database skiwi owner skiwi;
\c skiwi
create extension if not exists pgroonga;
SQL
