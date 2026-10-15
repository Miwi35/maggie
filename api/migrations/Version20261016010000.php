<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * Ending an errand archives what was bought instead of deleting it (MAG-488):
 * one errand per store, one purchased item per checked line.
 */
final class Version20261016010000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create errand and purchased_item, the history of finished errands';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE errand (id UUID NOT NULL, store_name VARCHAR(255) DEFAULT NULL, ended_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, item_count INT DEFAULT 0 NOT NULL, user_id UUID NOT NULL, store_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_536BA68DA76ED395 ON errand (user_id)');
        $this->addSql('CREATE INDEX IDX_536BA68DB092A811 ON errand (store_id)');
        $this->addSql('CREATE INDEX idx_errand_user_ended_at ON errand (user_id, ended_at)');
        $this->addSql('CREATE TABLE purchased_item (id UUID NOT NULL, label VARCHAR(255) NOT NULL, quantity DOUBLE PRECISION DEFAULT NULL, unit VARCHAR(20) DEFAULT NULL, packaging_unit VARCHAR(20) DEFAULT NULL, packaging_size DOUBLE PRECISION DEFAULT NULL, packaging_size_unit VARCHAR(20) DEFAULT NULL, position INT DEFAULT 0 NOT NULL, errand_id UUID NOT NULL, product_id UUID DEFAULT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_F8482141AD91EE0F ON purchased_item (errand_id)');
        $this->addSql('CREATE INDEX IDX_F84821414584665A ON purchased_item (product_id)');
        $this->addSql('ALTER TABLE errand ADD CONSTRAINT FK_536BA68DA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE errand ADD CONSTRAINT FK_536BA68DB092A811 FOREIGN KEY (store_id) REFERENCES store (id) ON DELETE SET NULL NOT DEFERRABLE');
        $this->addSql('ALTER TABLE purchased_item ADD CONSTRAINT FK_F8482141AD91EE0F FOREIGN KEY (errand_id) REFERENCES errand (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE purchased_item ADD CONSTRAINT FK_F84821414584665A FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE SET NULL NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE purchased_item DROP CONSTRAINT FK_F84821414584665A');
        $this->addSql('ALTER TABLE purchased_item DROP CONSTRAINT FK_F8482141AD91EE0F');
        $this->addSql('ALTER TABLE errand DROP CONSTRAINT FK_536BA68DB092A811');
        $this->addSql('ALTER TABLE errand DROP CONSTRAINT FK_536BA68DA76ED395');
        $this->addSql('DROP TABLE purchased_item');
        $this->addSql('DROP TABLE errand');
    }
}
