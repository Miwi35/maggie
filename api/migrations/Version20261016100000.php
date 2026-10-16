<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The aisles of each store, in the order of the way through it, and the
 * aisle of each product in each store (MAG-493). Nothing to backfill: every
 * product starts « Sans rayon ».
 */
final class Version20261016100000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Create aisle and product_aisle';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE aisle (id UUID NOT NULL, name VARCHAR(255) NOT NULL, position INT NOT NULL, store_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_8679761CB092A811 ON aisle (store_id)');
        $this->addSql('CREATE INDEX IDX_8679761CA76ED395 ON aisle (user_id)');
        $this->addSql('CREATE INDEX idx_aisle_store_position ON aisle (store_id, position)');
        $this->addSql('CREATE TABLE product_aisle (id UUID NOT NULL, product_id UUID NOT NULL, store_id UUID NOT NULL, aisle_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_27250D404584665A ON product_aisle (product_id)');
        $this->addSql('CREATE INDEX IDX_27250D40B092A811 ON product_aisle (store_id)');
        $this->addSql('CREATE INDEX IDX_27250D4023C1884A ON product_aisle (aisle_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_product_aisle_store ON product_aisle (product_id, store_id)');
        $this->addSql('ALTER TABLE aisle ADD CONSTRAINT FK_8679761CB092A811 FOREIGN KEY (store_id) REFERENCES store (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE aisle ADD CONSTRAINT FK_8679761CA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
        $this->addSql('ALTER TABLE product_aisle ADD CONSTRAINT FK_27250D404584665A FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE product_aisle ADD CONSTRAINT FK_27250D40B092A811 FOREIGN KEY (store_id) REFERENCES store (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE product_aisle ADD CONSTRAINT FK_27250D4023C1884A FOREIGN KEY (aisle_id) REFERENCES aisle (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('DROP TABLE product_aisle');
        $this->addSql('DROP TABLE aisle');
    }
}
