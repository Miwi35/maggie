<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Savings advances (MAG-276).
 *
 * `treasury_settings` holds the day the salary is expected, one row per user,
 * created on first read; nullable with no default, so nobody is « late » before
 * saying when they are paid. `transaction.repays_id` lets the debit leg of a
 * repayment point at the debit leg of the advance it gives back. Additive only.
 */
final class Version20261013080000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add treasury_settings (salary expected day) and transaction.repays_id';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE treasury_settings (id UUID NOT NULL, salary_expected_day INT DEFAULT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_treasury_settings_user ON treasury_settings (user_id)');
        $this->addSql('ALTER TABLE treasury_settings ADD CONSTRAINT FK_DCB74989A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE transaction ADD repays_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE transaction ADD CONSTRAINT FK_723705D19BDFCC18 FOREIGN KEY (repays_id) REFERENCES transaction (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_723705D19BDFCC18 ON transaction (repays_id)');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE transaction DROP CONSTRAINT FK_723705D19BDFCC18');
        $this->addSql('DROP INDEX IDX_723705D19BDFCC18');
        $this->addSql('ALTER TABLE transaction DROP repays_id');
        $this->addSql('ALTER TABLE treasury_settings DROP CONSTRAINT FK_DCB74989A76ED395');
        $this->addSql('DROP TABLE treasury_settings');
    }
}
