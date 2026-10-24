<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * What Maggie already told the owner about a product missing for a meal
 * (MAG-570): one row per product and meal, so the interruption fires once.
 */
final class Version20261023160000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add shortage_alert';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE shortage_alert (id UUID NOT NULL, resolved_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, meal_id UUID NOT NULL, product_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_74D08F3AA76ED395 ON shortage_alert (user_id)');
        $this->addSql('CREATE INDEX IDX_74D08F3A639666D6 ON shortage_alert (meal_id)');
        $this->addSql('CREATE INDEX IDX_74D08F3A4584665A ON shortage_alert (product_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_shortage_alert_meal_product ON shortage_alert (meal_id, product_id)');
        $this->addSql('ALTER TABLE shortage_alert ADD CONSTRAINT FK_74D08F3AA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE shortage_alert ADD CONSTRAINT FK_74D08F3A639666D6 FOREIGN KEY (meal_id) REFERENCES meal (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE shortage_alert ADD CONSTRAINT FK_74D08F3A4584665A FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE shortage_alert DROP CONSTRAINT FK_74D08F3AA76ED395');
        $this->addSql('ALTER TABLE shortage_alert DROP CONSTRAINT FK_74D08F3A639666D6');
        $this->addSql('ALTER TABLE shortage_alert DROP CONSTRAINT FK_74D08F3A4584665A');
        $this->addSql('DROP TABLE shortage_alert');
    }
}
