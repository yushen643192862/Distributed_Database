CREATE TABLE student (sid INT PRIMARY KEY, name CHAR(20), age INT, dept CHAR(20)) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
SHOW SHARDS student;
SHOW CLUSTER;

INSERT INTO student VALUES (1001, 'Alice', 20, 'CS');
INSERT INTO student VALUES (1002, 'Bob', 21, 'Math');
INSERT INTO student VALUES (1003, 'Cindy', 22, 'CS');
INSERT INTO student VALUES (1004, 'David', 23, 'EE');
INSERT INTO student VALUES (1005, 'Eva', 24, 'CS');
SELECT * FROM student;
SELECT sid, name FROM student WHERE dept = 'CS';
SELECT * FROM student WHERE sid = 1001;

UPDATE student SET age = 25 WHERE sid = 1005;
DELETE FROM student WHERE sid = 1002;
SELECT * FROM student;

CREATE TABLE course (cid INT PRIMARY KEY, sid INT, cname CHAR(20)) SHARD BY HASH(sid) SHARDS 3 REPLICAS 3;
INSERT INTO course VALUES (1, 1001, 'Database');
INSERT INTO course VALUES (2, 1003, 'Network');
INSERT INTO course VALUES (3, 1005, 'OS');
SELECT student.name, course.cname FROM student JOIN course ON student.sid = course.sid;

SELECT * FROM student WHERE sid = 1001;
SELECT * FROM student WHERE sid = 1001;
SELECT * FROM student WHERE sid = 1001;
SHOW NODES;

FAIL NODE dn2;
SHOW CLUSTER;
SELECT * FROM student WHERE sid = 1001;
INSERT INTO student VALUES (1006, 'Frank', 20, 'CS');
SHOW SHARDS student;

RECOVER NODE dn2;
SHOW CLUSTER;
SELECT * FROM student WHERE sid = 1006;

DROP TABLE course;
SHOW SHARDS;
exit
