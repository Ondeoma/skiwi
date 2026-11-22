# 構築・運用ガイド（VM 3台構成）

設定ファイルのひな形は [deploy/vm/](../deploy/vm/) にあります。IPアドレスは例です（`10.0.0.11〜13` が各VM、`10.0.0.10` がVIP）。
パッケージ名・パスはDebian/Ubuntu系で書いています（RHEL系の違いは注記）。

> 実機（VM・S3 互換ストレージ・IdP）での構築は未検証です。同じ構成をDocker上で再現した試験環境（`docker-compose.ha.yml`）で、
> 障害試験（アプリノード停止・強制終了、DBプライマリ停止・強制終了、復帰）は通過しています。

## 1. 全体像

```
                  利用者 ──HTTPS──▶ VIP 10.0.0.10 (keepalived)
                                        │
   ┌────────────────────┬───────────────┴──────┬────────────────────┐
   │ skiwi1 10.0.0.11    │ skiwi2 10.0.0.12      │ skiwi3 10.0.0.13    │
   │  HAProxy :443      │  HAProxy             │  HAProxy           │  ← 3台とも同じ設定。VIPを持つ1台が受ける
   │  skiwi-app :8080    │  skiwi-app            │  skiwi-app          │  ← ページの担当ノードへアプリが内部転送
   │  Patroni+PG17 :5432│  Patroni+PG17        │  Patroni+PG17      │  ← 1台がプライマリ、2台がレプリカ
   │  etcd :2379        │  etcd                │  etcd              │  ← Patroniのリーダー選出
   │  S3互換 :8000      │  S3互換              │  S3互換            │  ← 添付。VIP:7480で分散（外部のストレージでもよい）
   └────────────────────┴──────────────────────┴────────────────────┘
```

| 役割 | 冗長性 | 1台停止時 |
|---|---|---|
| HAProxy + keepalived | VIPが生きているVMへ移る | 数秒で切替 |
| skiwi-app | 3台すべてで稼働。ページごとに担当ノード | 正常停止なら即時、強制停止なら約15秒で担当が移る。クライアントは自動再接続 |
| PostgreSQL（Patroni） | 非同期レプリケーション、自動フェイルオーバー | プライマリ停止時は約30〜40秒（ttl=30）書き込み停止。非同期のため直前の数件のトランザクションが失われ得る（RPO小） |
| etcd | 3台で合意（過半数） | 1台停止は影響なし。2台停止でPatroniは新たな昇格をしない（`failsafe_mode`で現プライマリは維持） |
| S3 互換ストレージ | ストレージ側で3台に複製（1台停止でも読み書きできる設定にする） | 読み書き継続。復帰後に自動回復 |

### 推奨リソース（利用者100名・同時20名）
各VM: 4 vCPU / 8 GB RAM / OS 40 GB ＋ PostgreSQL用 50 GB ＋ 各VMでストレージを動かす場合はその分のディスク（複製を含めて添付の想定容量を収められる量）。

### 通信ポート（VM間）
| ポート | 用途 |
|---|---|
| 443/80 | 利用者 → HAProxy |
| 8080 | HAProxy・他ノード → skiwi-app（ノード間WebSocket転送を含む） |
| 5432 | skiwi-app → PostgreSQL、レプリケーション |
| 8008 | Patroni REST API |
| 2379/2380 | etcd |
| 8000, 7480(VIP) | S3 互換ストレージ（各VMで動かす場合。ストレージ自身のノード間通信も別に許可する） |
| VRRP（IPプロトコル112） | keepalived |

## 2. 構築手順

### 2.1 共通
- 時刻同期（chrony）、ホスト名解決、ファイアウォール（上表のポートを社内セグメントのみ許可）。
- Java 25（`openjdk-25-jre-headless` または Temurin 25）。

### 2.2 etcd（3台）
1. etcd 3.5 を導入し、[etcd.env](../deploy/vm/etcd.env) を各ノード用に書き換えて配置。
2. 3台で `systemctl enable --now etcd`。`etcdctl endpoint health --cluster` で確認。

### 2.3 PostgreSQL 17 + PGroonga + Patroni（3台）
1. PostgreSQL 17（PGDG リポジトリ）と PGroonga（groonga公式リポジトリの `postgresql-17-pgdg-pgroonga`）を導入。
   OS標準の `postgresql` サービスは無効化する（Patroniが起動する）。
2. Patroni: `pip install "patroni[etcd3]"`（または OS パッケージ）。[patroni.yml](../deploy/vm/patroni.yml) をノードごとに書き換えて配置。
   `post_bootstrap` には [deploy/docker/post_bootstrap.sh](../deploy/docker/post_bootstrap.sh) を使う（`SKIWI_DB_PASSWORD` を環境変数で渡す）。
3. 1台目から順に `systemctl enable --now patroni`。`patronictl -c /etc/patroni/patroni.yml list` で Leader 1台・Replica 2台（streaming）を確認。
4. アプリの接続先は3台を並べたJDBC URL（`targetServerType=primary`）。HAProxy経由にはしない（アプリがプライマリを選ぶ）。

> **PGroongaとレプリケーションの注意（障害試験で判明）**
> - PGroongaの全文検索索引はPostgreSQL本体のWALに載らない。`shared_preload_libraries = pgroonga_wal_resource_manager` と
>   `pgroonga.enable_wal_resource_manager = on` を**全ノード**に設定しないと、フェイルオーバー後の新プライマリで検索・ページ作成がエラーになる。
> - その独自WALレコードを `pg_rewind` が読めないため、強制終了した旧プライマリは巻き戻しに失敗する。
>   `remove_data_directory_on_rewind_failure: true` でデータを取り直して再参加させる（DBが小さい前提。大きくなったら所要時間に注意）。
> - `check_timeline: true` で、分岐したノード（再参加途中の旧プライマリ等）が昇格するのを防ぐ。これがないと、強制終了を続けた試験でデータが失われた。

### 2.4 S3 互換ストレージ
1. 冗長化した S3 互換のオブジェクトストレージを用意する。各VMで動かす場合は、3台に複製して1台停止でも読み書きできる設定にし、
   各VMの `:8000` で S3 API を受ける（[haproxy.cfg](../deploy/vm/haproxy.cfg) の `s3` が VIP:7480 にまとめる）。外部のストレージでもよい。
2. パス形式（`https://host/bucket/key`）でアクセスできること。
3. アプリ用のアクセスキーを発行し、`skiwi`（添付）と `skiwi-backup`（DBバックアップ）バケットを作成。
   アプリは起動時に `skiwi` バケットがなければ作る。

### 2.5 skiwi-app（3台）
1. 開発機で `scripts/dist.sh` → `dist/`（`lib/*.jar` と `static/`）を各VMの `/opt/skiwi/releases/<日付>/` に配置し、`/opt/skiwi/current` をシンボリックリンクで向ける。
2. `useradd -r -s /usr/sbin/nologin skiwi`。[skiwi.env](../deploy/vm/skiwi.env) を `/etc/skiwi/skiwi.env`（0600）に、
   [skiwi.service](../deploy/vm/skiwi.service) を `/etc/systemd/system/` に置き、`NODE_ID` / `NODE_URL` をノードごとに変える。
   `CLUSTER_SECRET` は3台共通の長いランダム値（`openssl rand -base64 32`）。
3. `systemctl enable --now skiwi`。初回起動時に1台がDBマイグレーションを行う（他はロック待ち）。
4. 確認: `curl http://10.0.0.11:8080/healthz` → `ok skiwi1`。管理者で `GET /api/admin/cluster` → `alive` に3台。

### 2.6 HAProxy + keepalived（3台）
1. [haproxy.cfg](../deploy/vm/haproxy.cfg) を3台に配置。証明書（鍵と連結したPEM）を `/etc/haproxy/certs/` に置く。
   VIPにbindするため `net.ipv4.ip_nonlocal_bind=1` を設定。
2. [keepalived.conf](../deploy/vm/keepalived.conf) の `priority` をノードごとに変えて配置。
3. `PUBLIC_URL` は `https://skiwi.example.com`（Cookieに Secure が付く）。DNSはVIPへ。

### 2.7 IdP（OIDC / SCIM）
README「IdP 側の設定」を参照（OIDC: Redirect URI `https://skiwi.example.com/auth/callback`、SCIMを使うなら sub = ユーザー名。SCIM: URL `https://skiwi.example.com/scim/v2`）。

## 3. バックアップ

| 対象 | 方法 | 頻度（例） |
|---|---|---|
| PostgreSQL | pgBackRest（[pgbackrest.conf](../deploy/vm/pgbackrest.conf)）。WALアーカイブ＋フルバックアップ。リポジトリは S3 互換ストレージの `skiwi-backup` | フル: 毎週、差分: 毎日、WAL: 常時 |
| 添付（`skiwi` バケット） | `rclone sync` で別の拠点（S3互換・NAS等）へ複製 | 毎日 |
| 設定 | `/etc/skiwi`, `/etc/patroni`, `/etc/haproxy`, `/etc/keepalived`、ストレージの設定 | 変更時 |

- pgBackRest: `repo1-cipher-pass` を決めてから（失うとバックアップを読めないので、別の場所にも控える）、初回 `pgbackrest --stanza=skiwi stanza-create`。バックアップは**プライマリでのみ**実行する
  （cronで `psql -Atc "select pg_is_in_recovery()"` が `f` のときだけ `pgbackrest --stanza=skiwi backup --type=diff`）。
- **同じストレージ上のバックアップだけでは不十分**（ストレージの全損に備えられない）。`skiwi-backup` も別の拠点へ複製すること。
- 復旧訓練: 年1回程度、別環境へ `pgbackrest restore` → アプリ起動で内容を確認する。

## 4. 日常運用

### アプリの更新（無停止）
1台ずつ:
1. HAProxyで対象を drain（`echo "set server skiwi/skiwi1 state drain" | socat stdio /run/haproxy/admin.sock`）。
2. `systemctl stop skiwi`（クラスタ登録を消して停止。担当ページは他ノードへ移り、利用者は自動再接続）。
3. `/opt/skiwi/current` を新リリースへ切替 → `systemctl start skiwi` → `/healthz` を確認 → HAProxyで `state ready`。
- DBマイグレーションを含む更新は、**後方互換なスキーマ変更**（列追加など）に限ること。旧バージョンが同時に動くため。

### DBの計画切替（OS更新など）
`patronictl -c /etc/patroni/patroni.yml switchover --leader skiwi1 --candidate skiwi2` → 対象ノードを保守 → 復帰後は自動でレプリカになる。

### 監視項目
| 対象 | 確認方法 |
|---|---|
| アプリ | `GET /healthz`（DBがプライマリで書ける状態なら200）、HAProxy統計（127.0.0.1:8404） |
| クラスタ | `GET /api/admin/cluster`（管理者。生存ノード、ページの担当、接続数） |
| DB | `patronictl list`（Leader 1・streaming 2、Lag）、ディスク使用量 |
| etcd | `etcdctl endpoint health --cluster` |
| S3 互換ストレージ | ストレージの稼働状態、使用率 |
| メール | `mail_outbox` の未送信件数（`select count(*) from mail_outbox where sent_at is null and not skipped`） |

### 障害時の挙動（試験済み、Docker上）
| 障害 | 利用者から見た挙動 |
|---|---|
| アプリ1台停止 | 編集画面が一瞬「接続中…」→ 自動で再接続。停止中に入力した内容も失われない |
| DBプライマリ停止・強制終了 | 最大30〜40秒ほど保存が止まる（画面は再接続を繰り返す）。復旧後に自動で再送され、本文の編集は失われない（本文以外の直前の数件の書き込みは失われ得る）。旧プライマリは復帰後に自動でレプリカとして再参加 |
| VIPを持つVMの停止 | 数秒で別VMがVIPを引き継ぐ。WebSocketは再接続 |

## 5. 試験環境（Docker）

```bash
scripts/ha-up.sh                       # etcd + Patroni×3 + アプリ×3 + HAProxy + ScalaS3 + Mailpit（入口 http://localhost:8090）
cd frontend && npm run test:ha         # 障害試験（正常停止）
HARD=1 npm run test:ha                 # 障害試験（強制終了）
docker compose -f docker-compose.ha.yml down
```
