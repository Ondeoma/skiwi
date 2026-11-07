// test/lib.mjs の型（e2e の TypeScript から使うため）
export declare const devPsql: (sql: string) => string
export declare function createSession(psql: (sql: string) => string, userId: string, ttl?: string): string
export declare function addToGroup(psql: (sql: string) => string, userId: string, group: string): void
export declare const caller: (base: string) => <T = any>(u: { token: string }, method: string, path: string, body?: unknown) => Promise<{ status: number; json: T | null }>
