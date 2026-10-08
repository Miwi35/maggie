<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Recurring operations (MAG-304): the series alone. Additive, and no table of
 * occurrences — they are computed, never stored.
 */
final class Version20261008210000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add recurring_operation table (series only, occurrences are computed)';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE recurring_operation (id UUID NOT NULL, label VARCHAR(255) NOT NULL, counterparty_name VARCHAR(255) DEFAULT NULL, counterparty_key VARCHAR(255) DEFAULT NULL, label_pattern VARCHAR(255) DEFAULT NULL, period VARCHAR(20) NOT NULL, anchor_on DATE NOT NULL, day_rule VARCHAR(20) NOT NULL, reference_amount_cents INT NOT NULL, reference_source VARCHAR(20) NOT NULL, amount_tolerance_percent INT NOT NULL, date_tolerance_days INT NOT NULL, ends_on DATE DEFAULT NULL, category_id UUID NOT NULL, account_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_73A53A5212469DE2 ON recurring_operation (category_id)');
        $this->addSql('CREATE INDEX IDX_73A53A529B6B5FBA ON recurring_operation (account_id)');
        $this->addSql('CREATE INDEX IDX_73A53A52A76ED395 ON recurring_operation (user_id)');
        $this->addSql('ALTER TABLE recurring_operation ADD CONSTRAINT FK_73A53A5212469DE2 FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recurring_operation ADD CONSTRAINT FK_73A53A529B6B5FBA FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE recurring_operation ADD CONSTRAINT FK_73A53A52A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE recurring_operation DROP CONSTRAINT FK_73A53A5212469DE2');
        $this->addSql('ALTER TABLE recurring_operation DROP CONSTRAINT FK_73A53A529B6B5FBA');
        $this->addSql('ALTER TABLE recurring_operation DROP CONSTRAINT FK_73A53A52A76ED395');
        $this->addSql('DROP TABLE recurring_operation');
    }
}
