"""API の説明書（OpenAPI）を作る: python3 scripts/gen-openapi.py frontend/public/openapi.json
API を変えたらここを直して作り直す。画面の「API の説明書」（/settings/api）はこのファイルを読む。"""
import json, sys
UUID = {"type": "string", "format": "uuid"}
def ref(n): return {"$ref": f"#/components/schemas/{n}"}
def arr(x): return {"type": "array", "items": x}
S = lambda **p: {"type": "object", "properties": p}
STR = {"type": "string"}; INT = {"type": "integer"}; BOOL = {"type": "boolean"}; NUM = {"type": "number"}
DT = {"type": "string", "format": "date-time"}; ANY = {}
schemas = {
 "Error": S(error=STR),
 "Page": S(id=UUID, workspaceId=UUID, parentId={**UUID, "nullable": True}, position=STR, title=STR, icon={**STR, "nullable": True},
           role={"type": "string", "enum": ["view", "edit"]}, updatedAt=DT, published=BOOL, labels=arr(STR), ownerId={**UUID, "nullable": True},
           ownerName={**STR, "nullable": True}, status={"type": "string", "enum": ["draft", "review", "published", "deprecated"], "nullable": True},
           database=BOOL, locked=BOOL),
 "Workspace": S(id=UUID, name=STR, role={"type": "string", "enum": ["view", "edit"]}, favorite=BOOL),
 "Content": S(content={"type": "object", "description": "本文（Tiptap / ProseMirror の JSON）"}, text={"type": "string", "description": "平文"}, updatedAt=DT),
 "Version": S(id=UUID, createdAt=DT, label={**STR, "nullable": True}, createdBy={**STR, "nullable": True}, authors=arr(STR)),
 "SearchResult": S(page=ref("Page"), snippet={**STR, "nullable": True, "description": "一致箇所を含む抜粋（HTML。キーワードのみ装飾）"}, fuzzy=BOOL),
 "Comment": S(id=UUID, threadId={**UUID, "nullable": True}, anchor={**STR, "nullable": True}, quote={**STR, "nullable": True}, body=STR,
              authorId={**UUID, "nullable": True}, authorName={**STR, "nullable": True}, createdAt=DT, updatedAt=DT, resolvedAt={**DT, "nullable": True}),
 "AclEntry": S(principalType={"type": "string", "enum": ["user", "group"]}, principalId=UUID, role={"type": "string", "enum": ["view", "edit"]},
               expiresAt={**DT, "nullable": True, "description": "権限の期限（過ぎると自動で外れる）"}),
 "Task": S(pageId=UUID, workspaceId=UUID, workspaceName=STR, pageTitle=STR, seq=INT, text=STR, checked=BOOL, due={"type": "string", "format": "date", "nullable": True}),
 "DbProp": S(id=STR, name=STR, type={"type": "string", "enum": ["text", "number", "select", "multi_select", "date", "person", "checkbox", "url", "relation", "rollup"]},
             options=arr(S(id=STR, name=STR, color=STR)), target={**UUID, "description": "relation: 関連先のデータベース"},
             relation={"type": "string", "description": "rollup: 関連の列のID"}, fn={"type": "string", "enum": ["count", "count_values", "sum", "avg", "min", "max", "checked"]},
             prop={"type": "string", "description": "rollup: 関連先の列のID"}),
 "Database": S(pageId=UUID, props=arr(ref("DbProp")), views=arr({"type": "object"}), rows=arr(S(page=ref("Page"), values={"type": "object", "description": "列のID → 値"})),
               users={"type": "object", "additionalProperties": STR}, canEdit=BOOL),
 "Attachment": S(id=UUID, url=STR, filename=STR, contentType=STR, size=INT),
 "BulkResult": S(done=arr(UUID), skipped=arr(UUID), failed=arr(S(id=UUID, title={**STR, "nullable": True}, error=STR))),
}
P = lambda name, desc, schema=UUID, where="path", req=True: {"name": name, "in": where, "required": req, "description": desc, "schema": schema}
ID = P("id", "ページID")
WS = P("wsId", "ワークスペースID")
paths = {}
def op(method, path, tag, summary, params=(), body=None, resp=None, status="200", desc=None):
    o = {"tags": [tag], "summary": summary, "parameters": list(params)}
    if desc: o["description"] = desc
    if body is not None:
        o["requestBody"] = {"required": True, "content": {"application/json": {"schema": body}}}
    r = {"description": "成功"}
    if resp is not None: r["content"] = {"application/json": {"schema": resp}}
    o["responses"] = {status: r, "401": {"description": "未認証"}, "403": {"description": "権限がない（閲覧のみ・読み取り専用トークンでの書き込み）"}, "404": {"description": "見つからない（閲覧権限がない場合も404）"}}
    paths.setdefault(path, {})[method] = o

op("get", "/api/me", "ユーザー", "自分の情報", resp=S(id=UUID, name=STR, email={**STR, "nullable": True}, groups=arr(STR), isAdmin=BOOL))
op("get", "/api/directory", "ユーザー", "ユーザー・グループの検索", [P("q", "名前・メール（ひらがな/カタカナ・全角/半角の違いは無視）", STR, "query")],
   resp=arr(S(principalType=STR, id=UUID, name=STR, detail={**STR, "nullable": True})))
op("get", "/api/workspaces", "ワークスペース", "見られるワークスペースの一覧", resp=arr(ref("Workspace")))
op("post", "/api/workspaces", "ワークスペース", "ワークスペースを作る", body=S(name=STR), resp=ref("Workspace"), status="201")
op("get", "/api/workspaces/{wsId}/pages", "ページ", "ワークスペースのページ一覧（閲覧できるもの）", [WS], resp=arr(ref("Page")))
op("post", "/api/workspaces/{wsId}/pages", "ページ", "ページを作る", [WS],
   body=S(title=STR, parentId={**UUID, "nullable": True}, afterId=UUID, placement={"type": "string", "enum": ["first", "last"]}, icon=STR), resp=ref("Page"), status="201",
   desc="本文は、作ったあとに POST /api/pages/{id}/content（Markdown）で入れる。")
op("get", "/api/pages/{id}", "ページ", "ページの情報", [ID], resp=ref("Page"))
op("patch", "/api/pages/{id}", "ページ", "タイトル・アイコン・所有者・状態を変える", [ID],
   body=S(title=STR, icon={**STR, "nullable": True}, ownerId={**UUID, "nullable": True}, status={"type": "string", "nullable": True}), resp=ref("Page"),
   desc="項目を省くと変えない。null で外す。")
op("post", "/api/pages/{id}/move", "ページ", "移動する（並べ替え・親の変更・別のワークスペースへ）", [ID],
   body=S(parentId={**UUID, "nullable": True}, afterId=UUID, placement=STR, workspaceId=UUID), resp=ref("Page"))
op("delete", "/api/pages/{id}", "ページ", "ゴミ箱へ移す（子ページも）", [ID], status="204")
op("get", "/api/pages/{id}/content", "ページ", "本文（JSON と平文）", [ID], resp=ref("Content"))
op("post", "/api/pages/{id}/content", "ページ", "本文を Markdown で書き換える", [ID],
   body=S(mode={"type": "string", "enum": ["replace", "append", "replace_section"]}, markdown=STR,
          heading={"type": "string", "description": "replace_section: 置き換える節の見出しの文字"}),
   resp=S(pageId=UUID, mode=STR, removed=INT, added=INT, blocks=INT),
   desc="replace は本文をすべて、replace_section は見出しから同じか上の階層の次の見出しの手前までを置き換える。append は末尾に足す。"
        "置き換える前の本文は版として残る。replace_section で見出しが見つからなければ 404。")
op("get", "/api/pages/{id}/versions", "ページ", "版の一覧", [ID], resp=arr(ref("Version")))
op("get", "/api/pages/{id}/backlinks", "ページ", "このページへリンクしているページ", [ID], resp=arr(ref("Page")))
op("put", "/api/pages/{id}/labels", "ページ", "ラベルを置き換える", [ID], body=S(labels=arr(STR)), resp=S(labels=arr(STR)))
op("post", "/api/pages/bulk", "ページ", "まとめて操作する（移動・削除・ラベル・所有者・状態）",
   body=S(ids=arr(UUID), action={"type": "string", "enum": ["move", "delete", "labels", "owner", "status"]}, parentId={**UUID, "nullable": True},
          workspaceId=UUID, addLabels=arr(STR), removeLabels=arr(STR), ownerId={**UUID, "nullable": True}, status={**STR, "nullable": True}),
   resp=ref("BulkResult"), desc="ページごとに権限を確かめる。できなかったものは failed に理由が入る。")
op("get", "/api/pages/{id}/watch", "ページ", "ウォッチの状態", [ID], resp=S(watching=BOOL, edits=BOOL, comments=BOOL, subtree=BOOL))
op("put", "/api/pages/{id}/watch", "ページ", "ウォッチの設定", [ID], body=S(watching=BOOL, edits=BOOL, comments=BOOL, subtree=BOOL))
op("get", "/api/pages/{id}/acl", "権限", "ページの共有設定（編集者のみ）", [ID], resp=S(inherit=BOOL, entries=arr(ref("AclEntry"))))
op("put", "/api/pages/{id}/acl", "権限", "ページの共有設定を置き換える", [ID], body=S(inherit=BOOL, entries=arr(ref("AclEntry"))), status="204")
op("get", "/api/pages/{id}/lock", "権限", "ページの保護の状態", [ID], resp=S(locked=BOOL, reason={**STR, "nullable": True}, canEdit=BOOL, canChange=BOOL))
op("put", "/api/pages/{id}/lock", "権限", "ページを保護する（保護した人・所有者・指定した人だけが編集できる）", [ID], body=S(reason=STR, editors=arr(UUID)))
op("delete", "/api/pages/{id}/lock", "権限", "保護を解除する", [ID], status="204")
op("get", "/api/search", "検索", "ページを検索する", [P("q", "検索語（空白区切りでAND）", STR, "query"), P("workspaceId", "ワークスペースで絞る", UUID, "query", False),
   P("label", "ラベル", STR, "query", False), P("days", "この日数以内に更新", INT, "query", False), P("status", "状態（none=状態なし）", STR, "query", False)],
   resp=arr(ref("SearchResult")))
op("get", "/api/pages/{id}/comments", "コメント", "コメントの一覧", [ID], resp=arr(ref("Comment")))
op("post", "/api/pages/{id}/comments", "コメント", "コメントする（threadId で返信）", [ID], body=S(body=STR, threadId=UUID), resp=ref("Comment"), status="201")
op("post", "/api/pages/{id}/attachments", "添付", "ファイルを添付する（multipart/form-data の file）", [ID], resp=ref("Attachment"), status="201")
op("get", "/api/attachments/{id}", "添付", "添付ファイルを取得する", [P("id", "添付ID")])
op("get", "/api/tasks", "ToDo", "自分に割り当てられたToDo", [P("done", "完了したものも含める", BOOL, "query", False)], resp=arr(ref("Task")))
op("get", "/api/pages/{id}/database", "データベース", "データベースの列・ビュー・項目", [ID], resp=ref("Database"))
op("put", "/api/pages/{id}/database", "データベース", "データベースにする・列とビューを変える", [ID], body=S(props=arr(ref("DbProp")), views=arr({"type": "object"})), resp=ref("Database"))
op("post", "/api/pages/{id}/database/rows", "データベース", "項目を追加する", [ID], body=S(title=STR, values={"type": "object"}), status="201")
op("post", "/api/pages/{id}/database/rows/bulk", "データベース", "項目をまとめて追加する（最大2000件）", [ID], body=S(rows=arr(S(title=STR, values={"type": "object"}))), resp=S(created=INT))
op("get", "/api/pages/{id}/props", "データベース", "項目の値（親のデータベースの列と一緒に）", [ID])
op("patch", "/api/pages/{id}/props", "データベース", "項目の値を変える（null で消す）", [ID], body=S(values={"type": "object", "description": "列のID → 値"}))
op("get", "/api/workspaces/{wsId}/health", "点検", "ページの被リンク数・最終更新（孤立・古いページの確認）", [WS])
op("get", "/api/workspaces/{wsId}/broken-links", "点検", "リンク切れ", [WS])
op("get", "/api/workspaces/{wsId}/webhooks", "連携", "外部への通知（Webhook）の一覧", [WS])
op("get", "/api/workspaces/{wsId}/automations", "連携", "自動化ルールの一覧", [WS])
op("post", "/api/hooks/{token}", "連携", "受信用の Webhook（認証不要。URL のトークンで判断）", [P("token", "受信用 URL のトークン", STR)],
   body=S(title=STR, text={"type": "string", "description": "本文（改行で段落）。comment ではコメント"}, values={"type": "object", "description": "row: 列名または列ID → 値"}),
   resp=S(pageId=UUID, url=STR), status="201", desc="ワークスペースの「📥 受信用の Webhook」で作った URL。作った人の権限で動く。1分に60件まで。")
op("get", "/api/tokens", "APIトークン", "自分のAPIトークン", resp=arr({"type": "object"}))

doc = {
 "openapi": "3.0.3",
 "info": {"title": "skiwi API", "version": "1.0",
          "description": "skiwi の REST API。認証は `Authorization: Bearer <APIトークン>`（サイドバー下部の「APIトークン」で発行）か、ログイン中のブラウザのCookie。\n\n"
                         "- 権限のないページ・ワークスペースは、存在を隠すため 404 を返す。閲覧はできるが編集できない場合は 403。\n"
                         "- 読み取り専用のトークンでは、書き込み（POST・PUT・PATCH・DELETE）は 403。\n"
                         "- 時刻は ISO 8601（UTC）。エラーは `{ \"error\": \"…\" }`。"},
 "servers": [{"url": "/"}],
 "components": {"securitySchemes": {"bearer": {"type": "http", "scheme": "bearer", "description": "APIトークン"}, "cookie": {"type": "apiKey", "in": "cookie", "name": "skiwi_session"}},
                "schemas": schemas},
 "security": [{"bearer": []}, {"cookie": []}],
 "tags": [{"name": t} for t in ["ユーザー", "ワークスペース", "ページ", "権限", "検索", "コメント", "添付", "ToDo", "データベース", "点検", "連携", "APIトークン"]],
 "paths": paths,
}
paths["/api/hooks/{token}"]["post"]["security"] = []
json.dump(doc, open(sys.argv[1], "w"), ensure_ascii=False, indent=1)
print(len(paths), "paths")
