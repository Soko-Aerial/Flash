import json
import sqlite3
import time
import os
import random
import string

SCHEMA_FILE = r"core\persistence\schemas\com.transfer.flash.core.persistence.db.FlashDatabase\20.json"

def create_schema(conn):
    with open(SCHEMA_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    entities = data["database"]["entities"]
    for entity in entities:
        sql = entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])
        conn.execute(sql)
        for idx in entity.get("indices", []):
            idx_sql = idx["createSql"].replace("${TABLE_NAME}", entity["tableName"])
            conn.execute(idx_sql)
    conn.commit()

TOP_QUERIES = [
    ("1. historyBefore (MessageDao)", 
     "EXPLAIN QUERY PLAN SELECT * FROM messages WHERE conversationId = 'conv1' AND deletedAt IS NULL AND (sentAt < 1000 OR (sentAt = 1000 AND localId < 'id1')) ORDER BY sentAt DESC, localId DESC LIMIT 50"),
    ("2. observeConversation (MessageDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM messages WHERE conversationId = 'conv1' AND deletedAt IS NULL ORDER BY sentAt DESC, localId DESC"),
    ("3. observeUnreadCounts (MessageDao)",
     "EXPLAIN QUERY PLAN SELECT m.conversationId AS conversationId, COUNT(*) AS unread FROM messages m JOIN conversations c ON c.id = m.conversationId WHERE m.senderId != 'user_self' AND m.deletedAt IS NULL AND (c.lastReadCursor IS NULL OR m.sentAt > (SELECT sentAt FROM messages WHERE localId = c.lastReadCursor)) GROUP BY m.conversationId"),
    ("4. observeLatestPreviews (MessageDao)",
     "EXPLAIN QUERY PLAN SELECT conversationId AS conversationId, text AS previewText, MAX(sentAt) AS sentAt FROM messages WHERE deletedAt IS NULL GROUP BY conversationId"),
    ("5. searchMessages (MessageDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM messages WHERE deletedAt IS NULL AND ((text NOT LIKE 'vn1:%' AND text NOT LIKE 'call1:%' AND text LIKE '%budget%' ESCAPE '\\') OR attachmentName LIKE '%budget%' ESCAPE '\\') ORDER BY sentAt DESC, localId DESC LIMIT 100"),
    ("6. searchConversationMessages (MessageDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM messages WHERE conversationId = 'conv1' AND deletedAt IS NULL AND ((text NOT LIKE 'vn1:%' AND text NOT LIKE 'call1:%' AND text LIKE '%budget%' ESCAPE '\\') OR attachmentName LIKE '%budget%' ESCAPE '\\') ORDER BY sentAt DESC, localId DESC LIMIT 100"),
    ("7. dueForDelivery (OutboxDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM outbox WHERE nextAttemptAt <= 1000 ORDER BY nextAttemptAt ASC LIMIT 50"),
    ("8. observeDeliveryCounts (GroupDeliveryDao)",
     "EXPLAIN QUERY PLAN SELECT gd.messageId AS messageId, SUM(CASE WHEN gd.state = 'DELIVERED' THEN 1 ELSE 0 END) AS deliveredTo, COUNT(*) AS deliveredTotal FROM group_deliveries gd INNER JOIN messages m ON m.localId = gd.messageId WHERE m.conversationId = 'grp1' AND m.senderId = 'user_self' GROUP BY gd.messageId"),
    ("9. pendingForMessage (GroupDeliveryDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM group_deliveries WHERE messageId = 'm1' AND state != 'DELIVERED' ORDER BY nextAttemptAt, memberId"),
    ("10. searchMembers (OrgDirectoryDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM org_directory_member WHERE displayNameFolded LIKE 'al%' OR displayNameFolded LIKE '% al%' ORDER BY displayName ASC LIMIT 50"),
    ("11. listMembersByDepartment (OrgDirectoryDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM org_directory_member WHERE departmentId = 'eng' ORDER BY displayName ASC"),
    ("12. listAllMembers (OrgDirectoryDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM org_directory_member ORDER BY displayName ASC"),
    ("13. loadContentForGroup (SwarmDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM swarm_content WHERE groupId = 'grp1' ORDER BY createdAtMs DESC"),
    ("14. loadAllContent (SwarmDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM swarm_content ORDER BY createdAtMs DESC"),
    ("15. observeRecent (HubFileDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM hub_file ORDER BY createdAtMs DESC LIMIT 50"),
    ("16. runnable (HubFileDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM hub_file WHERE state IN ('UPLOADING', 'READY', 'DOWNLOADING') AND paused = 0"),
    ("17. observeAll (ConversationDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM conversations ORDER BY pinned DESC, sortOrder DESC"),
    ("18. restorable (TransferDao)",
     "EXPLAIN QUERY PLAN SELECT * FROM transfers WHERE direction != '' AND dismissed = 0 AND updatedAtMs >= 1000 ORDER BY updatedAtMs DESC"),
    ("19. doneChunks (TransferChunkDao)",
     "EXPLAIN QUERY PLAN SELECT chunkIndex FROM transfer_chunks WHERE transferId = 't1' AND done = 1 ORDER BY chunkIndex ASC"),
    ("20. observeForConversation (ReactionDao)",
     "EXPLAIN QUERY PLAN SELECT r.* FROM reactions r INNER JOIN messages m ON r.messageId = m.localId WHERE m.conversationId = 'conv1' ORDER BY r.messageId, r.emoji"),
]

def explain_all():
    conn = sqlite3.connect(":memory:")
    create_schema(conn)
    print("=== EXPLAIN QUERY PLAN FOR TOP 20 QUERIES ===")
    results = []
    for name, sql in TOP_QUERIES:
        cur = conn.cursor()
        cur.execute(sql)
        plan = cur.fetchall()
        print(f"\n{name}:")
        plan_str = " | ".join([f"selectid={row[0]} order={row[1]} from={row[2]} detail={row[3]}" for row in plan])
        print(f"  {plan_str}")
        results.append((name, plan_str))
    conn.close()
    return results

def benchmark_message_counts():
    counts = [1000, 10000, 100000]
    words = ["hello", "project", "budget", "update", "report", "meeting", "invoice", "schedule", "design", "review"]
    
    print("\n=== BENCHMARK: LIKE '%x%' SCAN vs FTS5 at 1k, 10k, 100k MESSAGES ===")
    
    for count in counts:
        db_path = f"bench_db_{count}.db"
        if os.path.exists(db_path):
            os.remove(db_path)
        conn = sqlite3.connect(db_path)
        create_schema(conn)
        
        # Also create FTS5 table
        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(text, attachmentName, content='messages', content_rowid='rowid')")
        
        # Populate
        print(f"\nPopulating {count} messages...")
        batch = []
        conversations = [f"conv_{i}" for i in range(20)]
        for i in range(count):
            localId = f"msg_{i:08d}"
            convId = conversations[i % 20]
            senderId = "user_self" if (i % 3 == 0) else f"user_{i % 5}"
            # Pick 10 random words
            txt = " ".join(random.choices(words, k=8))
            sentAt = 1000000 + i
            status = "SENT"
            attachName = f"file_{i}.pdf" if (i % 10 == 0) else None
            batch.append((localId, convId, senderId, "Sender", txt, sentAt, status, None, None, None, attachName, None, 100 if attachName else 0, None, None, None, None, None, None, None))
        
        conn.executemany("INSERT INTO messages VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch)
        conn.execute("INSERT INTO messages_fts(rowid, text, attachmentName) SELECT rowid, text, attachmentName FROM messages")
        conn.commit()
        
        db_size_mb = os.path.getsize(db_path) / (1024 * 1024)
        
        # Test 1: searchMessages with LIKE '%budget%' (common word, early LIMIT exit)
        # Test 1b: searchMessages with LIKE '%nonexistent_rare_keyword%' (full table scan of all N rows)
        rare_sql = """
        SELECT * FROM messages WHERE deletedAt IS NULL AND (
            (text NOT LIKE 'vn1:%' AND text NOT LIKE 'call1:%' AND text LIKE '%nonexistent_rare_keyword%' ESCAPE '\\') OR
            attachmentName LIKE '%nonexistent_rare_keyword%' ESCAPE '\\'
        ) ORDER BY sentAt DESC, localId DESC LIMIT 100
        """
        rare_times = []
        for _ in range(5):
            t0 = time.perf_counter()
            rows = conn.execute(rare_sql).fetchall()
            t1 = time.perf_counter()
            rare_times.append((t1 - t0) * 1000)
        median_rare_like = sorted(rare_times)[len(rare_times)//2]

        fts_rare_sql = """
        SELECT m.* FROM messages_fts f
        JOIN messages m ON m.rowid = f.rowid
        WHERE messages_fts MATCH 'nonexistent_rare_keyword' AND m.deletedAt IS NULL
        ORDER BY m.sentAt DESC, m.localId DESC LIMIT 100
        """
        fts_rare_times = []
        for _ in range(5):
            t0 = time.perf_counter()
            rows = conn.execute(fts_rare_sql).fetchall()
            t1 = time.perf_counter()
            fts_rare_times.append((t1 - t0) * 1000)
        median_rare_fts = sorted(fts_rare_times)[len(fts_rare_times)//2]
        query_sql = """
        SELECT * FROM messages WHERE deletedAt IS NULL AND (
            (text NOT LIKE 'vn1:%' AND text NOT LIKE 'call1:%' AND text LIKE '%budget%' ESCAPE '\\') OR
            attachmentName LIKE '%budget%' ESCAPE '\\'
        ) ORDER BY sentAt DESC, localId DESC LIMIT 100
        """
        
        # Warmup
        conn.execute(query_sql).fetchall()
        
        # Measure 5 runs
        like_times = []
        for _ in range(5):
            t0 = time.perf_counter()
            rows = conn.execute(query_sql).fetchall()
            t1 = time.perf_counter()
            like_times.append((t1 - t0) * 1000)
        
        median_like = sorted(like_times)[len(like_times)//2]
        
        # Test 2: FTS5 query
        fts_sql = """
        SELECT m.* FROM messages_fts f
        JOIN messages m ON m.rowid = f.rowid
        WHERE messages_fts MATCH 'budget' AND m.deletedAt IS NULL
        ORDER BY m.sentAt DESC, m.localId DESC LIMIT 100
        """
        conn.execute(fts_sql).fetchall()
        fts_times = []
        for _ in range(5):
            t0 = time.perf_counter()
            rows = conn.execute(fts_sql).fetchall()
            t1 = time.perf_counter()
            fts_times.append((t1 - t0) * 1000)
            
        median_fts = sorted(fts_times)[len(fts_times)//2]
        
        # Test 3: observeUnreadCounts
        # Populate conversations
        conv_batch = [(c, f"Title {c}", False, False, False, False, 1000, f"msg_{random.randint(0, count-1):08d}", 1) for c in conversations]
        conn.executemany("INSERT OR REPLACE INTO conversations(id, title, isGroup, pinned, muted, archived, sortOrder, lastReadCursor, groupProto) VALUES (?,?,?,?,?,?,?,?,?)", conv_batch)
        conn.commit()
        
        unread_sql = """
        SELECT m.conversationId AS conversationId, COUNT(*) AS unread FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        WHERE m.senderId != 'user_self' AND m.deletedAt IS NULL AND (
            c.lastReadCursor IS NULL OR
            m.sentAt > (SELECT sentAt FROM messages WHERE localId = c.lastReadCursor)
        ) GROUP BY m.conversationId
        """
        conn.execute(unread_sql).fetchall()
        unread_times = []
        for _ in range(5):
            t0 = time.perf_counter()
            rows = conn.execute(unread_sql).fetchall()
            t1 = time.perf_counter()
            unread_times.append((t1 - t0) * 1000)
        median_unread = sorted(unread_times)[len(unread_times)//2]
        
        # Test 4: observeLatestPreviews
        preview_sql = """
        SELECT conversationId AS conversationId, text AS previewText, MAX(sentAt) AS sentAt
        FROM messages WHERE deletedAt IS NULL GROUP BY conversationId
        """
        conn.execute(preview_sql).fetchall()
        preview_times = []
        for _ in range(5):
            t0 = time.perf_counter()
            rows = conn.execute(preview_sql).fetchall()
            t1 = time.perf_counter()
            preview_times.append((t1 - t0) * 1000)
        median_preview = sorted(preview_times)[len(preview_times)//2]
        
        print(f"Results for N = {count:,} messages (DB file size: {db_size_mb:.2f} MB):")
        print(f"  LIKE scan (common, early exit): {median_like:.2f} ms")
        print(f"  LIKE scan (rare/miss, FULL scan): {median_rare_like:.2f} ms (runs: {[f'{x:.2f}' for x in rare_times]})")
        print(f"  FTS5 match (rare/miss, B-tree lookup): {median_rare_fts:.2f} ms (runs: {[f'{x:.2f}' for x in fts_rare_times]}) -> Speedup on miss: {median_rare_like / max(median_rare_fts, 0.01):.1f}x")
        print(f"  FTS5 match (common, 80% matches):  {median_fts:.2f} ms")
        print(f"  observeUnreadCounts:  {median_unread:.2f} ms (correlated subquery on {len(conversations)} convs)")
        print(f"  observeLatestPreviews:{median_preview:.2f} ms (GROUP BY on {count:,} rows)")
        
        conn.close()
        try:
            os.remove(db_path)
        except:
            pass

if __name__ == "__main__":
    explain_all()
    benchmark_message_counts()
