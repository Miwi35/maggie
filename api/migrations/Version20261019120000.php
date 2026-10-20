<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The review activity (MAG-456): where each user stands in the review of a
 * month, and the activity an event launches (the monthly review's appointment).
 */
final class Version20261019120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create review_progress, add activity to event';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE review_progress (id UUID NOT NULL, month DATE NOT NULL, step VARCHAR(20) DEFAULT \'review\' NOT NULL, handled_transaction_ids JSON DEFAULT \'[]\' NOT NULL, updated_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_1437B4DA76ED395 ON review_progress (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_review_progress_user_month ON review_progress (user_id, month)');
        $this->addSql('ALTER TABLE review_progress ADD CONSTRAINT FK_1437B4DA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE event ADD activity VARCHAR(40) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE event DROP activity');
        $this->addSql('ALTER TABLE review_progress DROP CONSTRAINT FK_1437B4DA76ED395');
        $this->addSql('DROP TABLE review_progress');
    }
}
