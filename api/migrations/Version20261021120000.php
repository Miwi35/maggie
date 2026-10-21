<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Detailed loans (MAG-522): a type, a projected flag and the conditions of the
 * offer. Existing rows become `amortizing_fixed`, not projected, every new
 * condition empty — their schedule is the one they had.
 */
final class Version20261021120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the type, the projected flag and the conditions of a loan';
    }

    public function up(Schema $schema): void
    {
        $this->addSql("ALTER TABLE loan ADD type VARCHAR(24) DEFAULT 'amortizing_fixed' NOT NULL");
        $this->addSql('ALTER TABLE loan ADD projected BOOLEAN DEFAULT false NOT NULL');
        $this->addSql('ALTER TABLE loan ADD initial_amount_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD apr_basis_points INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD duration_months INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD first_payment_on DATE DEFAULT NULL');
        $this->addSql("ALTER TABLE loan ADD periodicity VARCHAR(16) DEFAULT 'monthly' NOT NULL");
        $this->addSql('ALTER TABLE loan ADD insurance_rate_basis_points INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD insurance_monthly_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD fees_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD reference_index VARCHAR(64) DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD margin_basis_points INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD rate_cap_basis_points INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD rate_floor_basis_points INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD steps JSON DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD deferral_months INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD early_repayment_allowed BOOLEAN DEFAULT true NOT NULL');
        $this->addSql("ALTER TABLE loan ADD prepayment_penalty_mode VARCHAR(16) DEFAULT 'legal_cap' NOT NULL");
        $this->addSql('ALTER TABLE loan ADD prepayment_penalty_fixed_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD min_partial_repayment_cents INT DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD partial_repayment_effect VARCHAR(16) DEFAULT NULL');
        $this->addSql('ALTER TABLE loan ADD notice_days INT DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        foreach ([
            'type', 'projected', 'initial_amount_cents', 'apr_basis_points', 'duration_months', 'first_payment_on',
            'periodicity', 'insurance_rate_basis_points', 'insurance_monthly_cents', 'fees_cents', 'reference_index',
            'margin_basis_points', 'rate_cap_basis_points', 'rate_floor_basis_points', 'steps', 'deferral_months',
            'early_repayment_allowed', 'prepayment_penalty_mode', 'prepayment_penalty_fixed_cents',
            'min_partial_repayment_cents', 'partial_repayment_effect', 'notice_days',
        ] as $column) {
            $this->addSql("ALTER TABLE loan DROP {$column}");
        }
    }
}
