<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

final class Version20260916183743 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add envelope table (category budgets) for the finance module';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE envelope (id UUID NOT NULL, mode VARCHAR(20) NOT NULL, amount_cents INT NOT NULL, currency VARCHAR(3) NOT NULL, year INT NOT NULL, month INT DEFAULT NULL, category_id UUID NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_8A95786812469DE2 ON envelope (category_id)');
        $this->addSql('CREATE INDEX IDX_8A957868A76ED395 ON envelope (user_id)');
        $this->addSql('ALTER TABLE envelope ADD CONSTRAINT FK_8A95786812469DE2 FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE NOT DEFERRABLE');
        $this->addSql('ALTER TABLE envelope ADD CONSTRAINT FK_8A957868A76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE envelope DROP CONSTRAINT FK_8A95786812469DE2');
        $this->addSql('ALTER TABLE envelope DROP CONSTRAINT FK_8A957868A76ED395');
        $this->addSql('DROP TABLE envelope');
    }
}
