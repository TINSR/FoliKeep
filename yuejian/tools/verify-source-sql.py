"""Exercise actual Room SQL and exported schemas without an Android device."""
import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLES = (ROOT / 'core/database/src/main/kotlin/com/yuejian/database/AnnotationTables.kt').read_text(encoding='utf-8')
QUERIES = {}
for match in re.finditer(r'@Query\((?:"""(.*?)"""|"((?:\\.|[^"\\])*)")\)\s*(?:suspend\s+)?fun\s+(\w+)', TABLES, re.S):
    raw, quoted, name = match.groups()
    QUERIES[name] = raw if raw is not None else json.loads('"' + quoted + '"')

def database(version):
    schema = json.loads((ROOT / f'core/database/schemas/com.yuejian.database.ReaderDatabase/{version}.json').read_text())['database']
    db = sqlite3.connect(':memory:')
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity['indices']:
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return db

def seed(db):
    db.execute("INSERT INTO documents (id,revisionId,title,mediaType,contentHash,localPath,pageCount,lastPageIndex,lastOffset,zoom,panX,panY,createdAt,updatedAt,openedAt) VALUES ('d','r','book','application/pdf','hash','d.pdf',2,0,0,1,0,0,1,1,1)")
    db.execute("INSERT INTO anchors (id,documentId,revisionId,pageIndex,kind,quote,boundsJson,rotation,createdAt,colorKey) VALUES ('root','d','r',0,'text','original','[]',0,1,'gray')")
    db.execute("INSERT INTO conversations (id,anchorId,draftText,createdAt,updatedAt) VALUES ('conv','root','',1,1)")
    db.execute("INSERT INTO messages (id,conversationId,revision,role,content,status,createdAt,updatedAt) VALUES ('q','conv',1,'user','QUESTION','complete',1,1)")
    db.execute("INSERT INTO messages (id,conversationId,revision,role,content,status,createdAt,updatedAt) VALUES ('a','conv',1,'assistant','ANSWER','complete',2,2)")

class SourceSqlTest(unittest.TestCase):
    def setUp(self):
        self.db=database(11)
        seed(self.db)
        self.db.execute("INSERT INTO anchors (id,documentId,revisionId,pageIndex,kind,quote,boundsJson,rotation,createdAt,colorKey,conversationAnchorId) VALUES ('child','d','r',1,'text','DEFINITION n','[]',0,3,'gray','root')")
        self.db.execute("INSERT INTO messages (id,conversationId,revision,role,content,status,createdAt,updatedAt,sourceAnchorId) VALUES ('s','conv',1,'source','DEFINITION n','complete',3,3,'child')")
    def tearDown(self):
        self.db.close()
    def test_both_marks_open_one_conversation(self):
        for anchor in ['root','child']:
            row=self.db.execute(QUERIES['conversation'],{'id':anchor}).fetchone()
            self.assertEqual('conv',row['id'])
        rows=self.db.execute(QUERIES['catalogRows'],{'documentId':'d'}).fetchall()
        self.assertEqual(2,len(rows))
        self.assertEqual({'conv'},{r['convId'] for r in rows})
    def test_answer_search_has_one_hit_while_source_search_locates_child(self):
        hits=self.db.execute(QUERIES['searchRows'],{'documentId':'d','pattern':'%ANSWER%'}).fetchall()
        self.assertEqual(1,len(hits));self.assertEqual('root',hits[0]['anchorId'])
        hits=self.db.execute(QUERIES['searchRows'],{'documentId':'d','pattern':'%DEFINITION%'}).fetchall()
        self.assertEqual(1,len(hits));self.assertEqual('child',hits[0]['anchorId'])
    def test_removing_original_source_preserves_conversation_and_other_source(self):
        self.db.execute(QUERIES['removeSourceFromContext'],{'id':'root','at':4})
        self.assertEqual('conv',self.db.execute(QUERIES['conversation'],{'id':'child'}).fetchone()['id'])
        rows=self.db.execute(QUERIES['anchors'],{'id':'d'}).fetchall()
        self.assertEqual(['child'],[r['id'] for r in rows if r['contextRemovedAt'] is None])
    def test_delete_group_hides_all_marks_and_undo_restores_them(self):
        self.db.execute(QUERIES['deleteAnnotation'],{'id':'root','at':4})
        self.assertEqual([],self.db.execute(QUERIES['anchors'],{'id':'d'}).fetchall())
        self.db.execute(QUERIES['deleteAnnotation'],{'id':'root','at':None})
        self.assertEqual(2,len(self.db.execute(QUERIES['anchors'],{'id':'d'}).fetchall()))
    def test_migration_matches_new_schema_and_preserves_old_messages(self):
        old=database(10)
        try:
            seed(old)
            migration=TABLES.split('val MIGRATION_10_11 =',1)[1]
            for sql in re.findall(r'db.execSQL\("([^"]+)"\)',migration): old.execute(sql)
            for table in ['anchors','messages']:
                expected={r['name']:tuple(r)[2:6] for r in self.db.execute(f'PRAGMA table_info({table})')}
                actual={r['name']:tuple(r)[2:6] for r in old.execute(f'PRAGMA table_info({table})')}
                self.assertEqual(expected,actual)
            self.assertEqual('original',old.execute("SELECT quote FROM anchors WHERE id='root'").fetchone()[0])
            self.assertEqual(2,old.execute('SELECT COUNT(*) FROM messages').fetchone()[0])
        finally: old.close()

if __name__ == '__main__': unittest.main(verbosity=2)
