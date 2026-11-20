<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/** A task may come back (MAG-870): its rule, and the day of the rule the occurrence stands for. */
final class Version20261119130000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add task.rrule and task.recurrence_date';
    }

    public function up(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task ADD rrule VARCHAR(255) DEFAULT NULL');
        $this->addSql('ALTER TABLE task ADD recurrence_date DATE DEFAULT NULL');
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task DROP rrule');
        $this->addSql('ALTER TABLE task DROP recurrence_date');
    }
}
