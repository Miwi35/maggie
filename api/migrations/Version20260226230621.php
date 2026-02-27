<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260226230621 extends AbstractMigration
{
    public function getDescription(): string
    {
        return '';
    }

    public function up(Schema $schema): void
    {
        // this up() migration is auto-generated, please modify it to your needs
        $this->addSql('CREATE TABLE ciqual_food (id UUID NOT NULL, alim_code VARCHAR(10) NOT NULL, alim_name_fr VARCHAR(255) NOT NULL, alim_group_code VARCHAR(10) DEFAULT NULL, alim_group_name_fr VARCHAR(255) DEFAULT NULL, alim_ssgroup_code VARCHAR(10) DEFAULT NULL, alim_ssgroup_name_fr VARCHAR(255) DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_91E3008C6C039691 ON ciqual_food (alim_code)');
        $this->addSql('CREATE INDEX idx_ciqual_food_alim_code ON ciqual_food (alim_code)');
        $this->addSql('CREATE TABLE ciqual_food_nutrient (id UUID NOT NULL, value DOUBLE PRECISION DEFAULT NULL, confidence_code VARCHAR(5) DEFAULT NULL, raw_value VARCHAR(50) DEFAULT NULL, food_id UUID NOT NULL, nutrient_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_E345E30DBA8E87C4 ON ciqual_food_nutrient (food_id)');
        $this->addSql('CREATE INDEX IDX_E345E30D27373320 ON ciqual_food_nutrient (nutrient_id)');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_E345E30DBA8E87C427373320 ON ciqual_food_nutrient (food_id, nutrient_id)');
        $this->addSql('CREATE TABLE ciqual_nutrient (id UUID NOT NULL, const_code VARCHAR(10) NOT NULL, const_name_fr VARCHAR(255) NOT NULL, const_unit VARCHAR(20) DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX UNIQ_464EDF633EC01906 ON ciqual_nutrient (const_code)');
        $this->addSql('ALTER TABLE ciqual_food_nutrient ADD CONSTRAINT FK_E345E30DBA8E87C4 FOREIGN KEY (food_id) REFERENCES ciqual_food (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE ciqual_food_nutrient ADD CONSTRAINT FK_E345E30D27373320 FOREIGN KEY (nutrient_id) REFERENCES ciqual_nutrient (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE product ADD kcal_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD protein_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD carbs_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD fat_per100g DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD ciqual_food_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE product ADD CONSTRAINT FK_D34A04ADB76D9487 FOREIGN KEY (ciqual_food_id) REFERENCES ciqual_food (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('CREATE INDEX IDX_D34A04ADB76D9487 ON product (ciqual_food_id)');
    }

    public function down(Schema $schema): void
    {
        // this down() migration is auto-generated, please modify it to your needs
        $this->addSql('ALTER TABLE ciqual_food_nutrient DROP CONSTRAINT FK_E345E30DBA8E87C4');
        $this->addSql('ALTER TABLE ciqual_food_nutrient DROP CONSTRAINT FK_E345E30D27373320');
        $this->addSql('DROP TABLE ciqual_food');
        $this->addSql('DROP TABLE ciqual_food_nutrient');
        $this->addSql('DROP TABLE ciqual_nutrient');
        $this->addSql('ALTER TABLE product DROP CONSTRAINT FK_D34A04ADB76D9487');
        $this->addSql('DROP INDEX IDX_D34A04ADB76D9487');
        $this->addSql('ALTER TABLE product DROP kcal_per100g');
        $this->addSql('ALTER TABLE product DROP protein_per100g');
        $this->addSql('ALTER TABLE product DROP carbs_per100g');
        $this->addSql('ALTER TABLE product DROP fat_per100g');
        $this->addSql('ALTER TABLE product DROP ciqual_food_id');
    }
}
