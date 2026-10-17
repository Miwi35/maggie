<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * One notion, the operation (MAG-478): the table of the recurring operations
 * takes what a categorization rule recognised a line by — match type,
 * direction, amount bounds — its priority and its active state, and its
 * recurrence (period, anchor, reference amount, account) and label become
 * optional. The rules move in with Version20261017100100.
 */
final class Version20261017100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Let a recurring operation carry the criteria, priority and active state of a rule, and its recurrence be optional';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE recurring_operation ADD match_type VARCHAR(20) DEFAULT 'contains' NOT NULL");
        $this->addSql("ALTER TABLE recurring_operation ADD direction VARCHAR(20) DEFAULT 'any' NOT NULL");
        $this->addSql('ALTER TABLE recurring_operation ADD min_amount_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE recurring_operation ADD max_amount_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE recurring_operation ADD priority INT DEFAULT 0 NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ADD is_active BOOLEAN DEFAULT true NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER label DROP NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER period DROP NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER anchor_on DROP NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER reference_amount_cents DROP NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER account_id DROP NOT NULL');
    }

    public function down(Schema $schema): void
    {
        // Only once the operations without recurrence went back to their table (see Version20261017100100::down()).
        $this->addSql('ALTER TABLE recurring_operation ALTER label SET NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER period SET NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER anchor_on SET NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER reference_amount_cents SET NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation ALTER account_id SET NOT NULL');
        $this->addSql('ALTER TABLE recurring_operation DROP match_type');
        $this->addSql('ALTER TABLE recurring_operation DROP direction');
        $this->addSql('ALTER TABLE recurring_operation DROP min_amount_cents');
        $this->addSql('ALTER TABLE recurring_operation DROP max_amount_cents');
        $this->addSql('ALTER TABLE recurring_operation DROP priority');
        $this->addSql('ALTER TABLE recurring_operation DROP is_active');
    }
}
