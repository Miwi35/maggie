<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * A categorization rule may recognise a transaction on its sign and amount
 * alone, so its label pattern becomes optional (MAG-475).
 */
final class Version20261015130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Make categorization_rule.label_pattern nullable';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE categorization_rule ALTER label_pattern DROP NOT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql("UPDATE categorization_rule SET label_pattern = '' WHERE label_pattern IS NULL");
        $this->addSql('ALTER TABLE categorization_rule ALTER label_pattern SET NOT NULL');
    }
}
