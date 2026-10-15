<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;

/**
 * The alert schedule and the morning time of the task settings (MAG-407). The
 * rows already there take the spec's schedule and 08:00.
 */
final class Version20261015120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Add the alert schedule and the morning time to task_settings';
    }

    public function up(Schema $schema): void
    {
        $this->addSql(<<<'SQL'
            ALTER TABLE task_settings ADD alert_schedule JSON DEFAULT '{"critical":{"daysBefore":[3,1],"dueDay":true,"overdueEveryDays":1},"high":{"daysBefore":[1],"dueDay":true,"overdueEveryDays":1},"normal":{"daysBefore":[],"dueDay":true,"overdueEveryDays":2}}' NOT NULL
            SQL);
        $this->addSql('ALTER TABLE task_settings ALTER alert_schedule DROP DEFAULT');
        $this->addSql("ALTER TABLE task_settings ADD morning_time VARCHAR(5) DEFAULT '08:00' NOT NULL");
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE task_settings DROP alert_schedule');
        $this->addSql('ALTER TABLE task_settings DROP morning_time');
    }
}
