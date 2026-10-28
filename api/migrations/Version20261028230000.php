<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The network position goes to the bin (MAG-666, owner's decision of 28 Oct.): only the last GPS
 * point is kept. The network columns are dropped with what they held.
 */
final class Version20261028230000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Drop the network position from user_location: only GPS is kept';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_location DROP network_latitude');
        $this->addSql('ALTER TABLE user_location DROP network_longitude');
        $this->addSql('ALTER TABLE user_location DROP network_accuracy');
        $this->addSql('ALTER TABLE user_location DROP network_captured_at');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE user_location ADD network_latitude DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE user_location ADD network_longitude DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE user_location ADD network_accuracy DOUBLE PRECISION DEFAULT NULL');
        $this->addSql('ALTER TABLE user_location ADD network_captured_at TIMESTAMP(0) WITH TIME ZONE DEFAULT NULL');
    }
}
