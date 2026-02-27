<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260226230621 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add nutrition fields and ciqual_alim_code to product table';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product ADD kcal_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD protein_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD carbs_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD fat_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD ciqual_alim_code VARCHAR(10) DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE product DROP kcal_per100g');
        $this->addSql('ALTER TABLE product DROP protein_per100g');
        $this->addSql('ALTER TABLE product DROP carbs_per100g');
        $this->addSql('ALTER TABLE product DROP fat_per100g');
        $this->addSql('ALTER TABLE product DROP ciqual_alim_code');
    }
}
