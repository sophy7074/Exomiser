DROP TABLE TAD IF EXISTS;

CREATE TABLE TAD
(
    CHROMOSOME SMALLINT NOT NULL,
    START INTEGER NOT NULL,
    "end" INTEGER NOT NULL,
    ENTREZID INTEGER NOT NULL,
    SYMBOL VARCHAR(24)
);
CREATE INDEX TAD1 ON TAD (CHROMOSOME, START, "end");