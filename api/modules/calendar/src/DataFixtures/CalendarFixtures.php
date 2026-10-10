<?php

namespace Maggie\Calendar\DataFixtures;

use Doctrine\Bundle\FixturesBundle\Fixture;
use Doctrine\Persistence\ObjectManager;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskPriority;

class CalendarFixtures extends Fixture
{
    public function load(ObjectManager $manager): void
    {
        // Default agenda
        $agenda = new Agenda();
        $agenda->setName('Personal');
        $agenda->setDescription('Default personal calendar');
        $agenda->setTimeZone('Europe/Paris');
        $agenda->setColor('#4285F4');
        $agenda->setIsDefault(true);
        $manager->persist($agenda);

        // Work agenda
        $work = new Agenda();
        $work->setName('Work');
        $work->setDescription('Work meetings and deadlines');
        $work->setTimeZone('Europe/Paris');
        $work->setColor('#EA4335');
        $manager->persist($work);

        // Sample timed event — tomorrow at 10:00
        $tomorrow = new \DateTimeImmutable('tomorrow 10:00', new \DateTimeZone('Europe/Paris'));
        $meeting = new Event();
        $meeting->setSummary('Team standup');
        $meeting->setDescription('Daily team sync');
        $meeting->setStartAt($tomorrow);
        $meeting->setEndAt($tomorrow->modify('+30 minutes'));
        $meeting->setTimeZone('Europe/Paris');
        $meeting->setAgenda($work);
        $manager->persist($meeting);

        // Sample all-day event — next Monday
        $nextMonday = new \DateTimeImmutable('next monday', new \DateTimeZone('Europe/Paris'));
        $holiday = new Event();
        $holiday->setSummary('Day off');
        $holiday->scheduleAllDay($nextMonday);
        $holiday->setTimeZone('Europe/Paris');
        $holiday->setAgenda($agenda);
        $manager->persist($holiday);

        // Sample recurring event — weekly team lunch every Wednesday at 12:00
        $wednesday = new \DateTimeImmutable('next wednesday 12:00', new \DateTimeZone('Europe/Paris'));
        $lunch = new Event();
        $lunch->setSummary('Team lunch');
        $lunch->setDescription('Weekly team lunch');
        $lunch->setLocation('Restaurant Le Petit');
        $lunch->setStartAt($wednesday);
        $lunch->setEndAt($wednesday->modify('+1 hour'));
        $lunch->setTimeZone('Europe/Paris');
        $lunch->setRrule('FREQ=WEEKLY;BYDAY=WE');
        $lunch->setAgenda($work);
        $manager->persist($lunch);

        // Sample event with reminders
        $friday = new \DateTimeImmutable('next friday 14:00', new \DateTimeZone('Europe/Paris'));
        $dentist = new Event();
        $dentist->setSummary('Dentist appointment');
        $dentist->setLocation('Dr. Martin, 15 rue de la Paix');
        $dentist->setStartAt($friday);
        $dentist->setEndAt($friday->modify('+1 hour'));
        $dentist->setTimeZone('Europe/Paris');
        $dentist->setReminders([
            'useDefault' => false,
            'overrides' => [
                ['method' => 'popup', 'minutes' => 60],
                ['method' => 'popup', 'minutes' => 10],
            ],
        ]);
        $dentist->setAgenda($agenda);
        $manager->persist($dentist);

        // Tentative event
        $saturday = new \DateTimeImmutable('next saturday 18:00', new \DateTimeZone('Europe/Paris'));
        $dinner = new Event();
        $dinner->setSummary('Dinner with friends');
        $dinner->setLocation('TBD');
        $dinner->setStartAt($saturday);
        $dinner->setEndAt($saturday->modify('+2 hours'));
        $dinner->setTimeZone('Europe/Paris');
        $dinner->setStatus(EventStatus::Tentative);
        $dinner->setAgenda($agenda);
        $manager->persist($dinner);

        // --- Tasks ---

        $tz = new \DateTimeZone('Europe/Paris');

        // Task with due date (upcoming)
        $task1 = new Task();
        $task1->setTitle('Prepare quarterly report');
        $task1->setDescription('Compile Q1 metrics and create presentation');
        $task1->setPriority(TaskPriority::High);
        $task1->setCriticality(TaskCriticality::Medium);
        $task1->setDueDate(new \DateTimeImmutable('+3 days', $tz));
        $manager->persist($task1);

        // Task without due date
        $task2 = new Task();
        $task2->setTitle('Organize desk');
        $task2->setDescription('Clean up workspace and file documents');
        $task2->setPriority(TaskPriority::Low);
        $task2->setCriticality(TaskCriticality::Low);
        $manager->persist($task2);

        // Completed task
        $task3 = new Task();
        $task3->setTitle('Submit expense report');
        $task3->setPriority(TaskPriority::Medium);
        $task3->setCriticality(TaskCriticality::Medium);
        $task3->setDueDate(new \DateTimeImmutable('-1 day', $tz));
        $task3->setCompletedAt(new \DateTimeImmutable('-1 day 15:00', $tz));
        $manager->persist($task3);

        // Critical task
        $task4 = new Task();
        $task4->setTitle('Fix production deployment');
        $task4->setDescription('Hotfix for the authentication issue in production');
        $task4->setPriority(TaskPriority::High);
        $task4->setCriticality(TaskCriticality::Critical);
        $task4->setDueDate(new \DateTimeImmutable('tomorrow', $tz));
        $manager->persist($task4);

        $manager->flush();
    }
}
