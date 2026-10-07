<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The devices Maggie can reach by push (MAG-26): one row per FCM registration
 * token, unique across users — a token belongs to one install of the app, and
 * whoever registers it last owns it. Deleting a user deletes their devices.
 */
final class Version20261007043353 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Store the FCM tokens of the user\'s devices: device_token';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('CREATE TABLE device_token (id UUID NOT NULL, token VARCHAR(512) NOT NULL, platform VARCHAR(10) NOT NULL, device_name VARCHAR(100) DEFAULT NULL, created_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, last_seen_at TIMESTAMP(0) WITH TIME ZONE NOT NULL, user_id UUID NOT NULL, PRIMARY KEY (id))');
        $this->addSql('CREATE INDEX IDX_99B2415CA76ED395 ON device_token (user_id)');
        $this->addSql('CREATE UNIQUE INDEX uniq_device_token_token ON device_token (token)');
        $this->addSql('ALTER TABLE device_token ADD CONSTRAINT FK_99B2415CA76ED395 FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE NOT DEFERRABLE');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE device_token DROP CONSTRAINT FK_99B2415CA76ED395');
        $this->addSql('DROP TABLE device_token');
    }
}
