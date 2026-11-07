<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The question a `question` notification puts to the owner (MAG-717): its options and whether
 * several may be ticked, answered from the interruption.
 */
final class Version20261108090000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add notification.interaction';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification ADD interaction JSON DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE notification DROP interaction');
    }
}
