<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Auto-generated Migration: Please modify to your needs!
 */
final class Version20260227214604 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Replace Product ciqual_food FK with ciqual_alim_code string, drop unused tables';
    }

    public function up(Schema $schema): void
    {
        // Every drop here is guarded, because nothing in this migration chain
        // ever created what it removes: the ciqual tables, product.ciqual_food_id
        // and agent_message reached the dev database through schema:update, not
        // through a migration. Replaying the chain on an empty database — which
        // is what the e2e stack does on every start, and what a restore would
        // do — therefore failed here (MAG-94).
        //
        // IF EXISTS changes nothing where the migration already ran.
        $this->addSql('ALTER TABLE IF EXISTS product DROP CONSTRAINT IF EXISTS fk_d34a04adb76d9487');
        $this->addSql('DROP INDEX IF EXISTS idx_d34a04adb76d9487');
        // Version20260226230621 already adds this column the day before; on the
        // dev database that migration had not run when this one was written.
        $this->addSql('ALTER TABLE product ADD COLUMN IF NOT EXISTS ciqual_alim_code VARCHAR(10) DEFAULT NULL');
        $this->addSql('ALTER TABLE product DROP COLUMN IF EXISTS ciqual_food_id');

        // Drop ciqual tables (data moved to separate Ciqual microservice)
        $this->addSql('ALTER TABLE IF EXISTS ciqual_food_nutrient DROP CONSTRAINT IF EXISTS fk_e345e30dba8e87c4');
        $this->addSql('ALTER TABLE IF EXISTS ciqual_food_nutrient DROP CONSTRAINT IF EXISTS fk_e345e30d27373320');
        $this->addSql('DROP TABLE IF EXISTS ciqual_food_nutrient');
        $this->addSql('DROP TABLE IF EXISTS ciqual_food');
        $this->addSql('DROP TABLE IF EXISTS ciqual_nutrient');

        // Drop unused agent_message table (agent stores messages in Python)
        $this->addSql('DROP TABLE IF EXISTS agent_message');
    }

    public function down(Schema $schema): void
    {
        // Guarded to match up(). Without this, rolling back on a database
        // where up() no-opped would drop ciqual_alim_code — the column
        // Version20260226230621 owns — and that migration's own down() would
        // then fail on a column that is already gone.
        $this->addSql('CREATE TABLE IF NOT EXISTS agent_message (id VARCHAR(26) NOT NULL, user_id VARCHAR(36) NOT NULL, role VARCHAR(20) NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX idx_agent_message_user_created ON agent_message (user_id, created_at)');
        $this->addSql('CREATE TABLE IF NOT EXISTS ciqual_food (id UUID NOT NULL, alim_code VARCHAR(10) NOT NULL, alim_name_fr VARCHAR(255) NOT NULL, alim_group_code VARCHAR(10) DEFAULT NULL, alim_group_name_fr VARCHAR(255) DEFAULT NULL, alim_ssgroup_code VARCHAR(10) DEFAULT NULL, alim_ssgroup_name_fr VARCHAR(255) DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_91e3008c6c039691 ON ciqual_food (alim_code)');
        $this->addSql('CREATE INDEX idx_ciqual_food_alim_code ON ciqual_food (alim_code)');
        $this->addSql('CREATE TABLE IF NOT EXISTS ciqual_food_nutrient (id UUID NOT NULL, value DOUBLE PRECISION DEFAULT NULL, confidence_code VARCHAR(5) DEFAULT NULL, raw_value VARCHAR(50) DEFAULT NULL, food_id UUID NOT NULL, nutrient_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_e345e30dba8e87c427373320 ON ciqual_food_nutrient (food_id, nutrient_id)');
        $this->addSql('CREATE INDEX idx_e345e30d27373320 ON ciqual_food_nutrient (nutrient_id)');
        $this->addSql('CREATE INDEX idx_e345e30dba8e87c4 ON ciqual_food_nutrient (food_id)');
        $this->addSql('CREATE TABLE IF NOT EXISTS ciqual_nutrient (id UUID NOT NULL, const_code VARCHAR(10) NOT NULL, const_name_fr VARCHAR(255) NOT NULL, const_unit VARCHAR(20) DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE UNIQUE INDEX uniq_464edf633ec01906 ON ciqual_nutrient (const_code)');
        $this->addSql('ALTER TABLE ciqual_food_nutrient ADD CONSTRAINT fk_e345e30dba8e87c4 FOREIGN KEY (food_id) REFERENCES ciqual_food (id) ON DELETE CASCADE NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('ALTER TABLE ciqual_food_nutrient ADD CONSTRAINT fk_e345e30d27373320 FOREIGN KEY (nutrient_id) REFERENCES ciqual_nutrient (id) ON DELETE CASCADE NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('ALTER TABLE product ADD COLUMN IF NOT EXISTS ciqual_food_id UUID DEFAULT NULL');
        $this->addSql('ALTER TABLE product DROP COLUMN IF EXISTS ciqual_alim_code');
        $this->addSql('ALTER TABLE product ADD CONSTRAINT fk_d34a04adb76d9487 FOREIGN KEY (ciqual_food_id) REFERENCES ciqual_food (id) ON DELETE SET NULL NOT DEFERRABLE INITIALLY IMMEDIATE');
        $this->addSql('CREATE INDEX idx_d34a04adb76d9487 ON product (ciqual_food_id)');
    }
}
