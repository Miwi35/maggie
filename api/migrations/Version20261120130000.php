<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A proaction is a reminder or a check, and a completed run keeps how it reached
 * the owner, its topic and Maggie's verdict (MAG-886). Every existing one was a
 * reminder: it always interrupted.
 */
final class Version20261120130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add proaction.kind, delivery, topic_key, verdict, notice and message_link';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE proaction ADD kind VARCHAR(10) DEFAULT 'reminder' NOT NULL");
        $this->addSql('ALTER TABLE proaction ADD delivery VARCHAR(10) DEFAULT NULL');
        $this->addSql('ALTER TABLE proaction ADD topic_key VARCHAR(80) DEFAULT NULL');
        $this->addSql('ALTER TABLE proaction ADD verdict JSON DEFAULT NULL');
        $this->addSql('ALTER TABLE proaction ADD notice TEXT DEFAULT NULL');
        $this->addSql('ALTER TABLE proaction ADD message_link VARCHAR(120) DEFAULT NULL');
        $this->addSql('CREATE INDEX idx_proaction_topic_key_completed_at ON proaction (topic_key, completed_at)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP INDEX idx_proaction_topic_key_completed_at');
        $this->addSql('ALTER TABLE proaction DROP kind');
        $this->addSql('ALTER TABLE proaction DROP delivery');
        $this->addSql('ALTER TABLE proaction DROP topic_key');
        $this->addSql('ALTER TABLE proaction DROP verdict');
        $this->addSql('ALTER TABLE proaction DROP notice');
        $this->addSql('ALTER TABLE proaction DROP message_link');
    }
}
