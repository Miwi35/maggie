<?php

declare(strict_types=1);

namespace Maggie\Health\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\DailyActivity;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Message\RefreshHealthDayCommand;
use Maggie\Health\Service\DailyEnergyReader;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'app:health:rebuild-days',
    description: 'Rebuild the health journal\'s computed columns from the weighings, sessions, intakes and Health Connect days',
)]
class RebuildHealthDaysCommand extends Command
{
    public function __construct(
        private readonly UserRepository $users,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('user', null, InputOption::VALUE_REQUIRED, 'Only this user, by email')
            ->setHelp(<<<'HELP'
                Reads again, day by day, what the health journal (MAG-750) summarises:
                the first weighing not set aside, the steps, the kcal eaten and spent,
                the minutes of sport, the night's fast. Each day that has a source or a journal row is
                refreshed through the same command the domain events use, so the
                result is the one the events would have given. Mood, fatigue and the
                note are never touched. Running it twice changes nothing more.

                  <info>%command.full_name%</info>
                  <info>%command.full_name% --user=someone@example.com</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $email = $input->getOption('user');

        if (\is_string($email)) {
            $user = $this->users->findOneBy(['email' => $email]);
            if (null === $user) {
                $io->error("No user {$email}.");

                return Command::FAILURE;
            }
            $users = [$user];
        } else {
            $users = $this->users->findAll();
        }

        $refreshed = 0;
        foreach ($users as $user) {
            $userId = (string) $user->getId();
            foreach ($this->daysOf($user) as $date) {
                $this->bus->dispatch(new RefreshHealthDayCommand($userId, $date));
                ++$refreshed;
            }
            $this->em->clear();
        }

        $io->success("{$refreshed} day(s) refreshed.");

        return Command::SUCCESS;
    }

    /** @return list<string> every local day (YYYY-MM-DD) the user has a source or a journal row on, the oldest first */
    private function daysOf(User $user): array
    {
        $zone = new \DateTimeZone(DailyEnergyReader::TIME_ZONE);
        $days = [];

        foreach ($this->em->getRepository(BodyMeasurement::class)->findBy(['user' => $user]) as $measurement) {
            $days[$measurement->getMeasuredAt()->setTimezone($zone)->format('Y-m-d')] = true;
        }
        foreach ($this->em->getRepository(Workout::class)->findBy(['user' => $user]) as $workout) {
            if (WorkoutSource::Planned !== $workout->getSource()) {
                $days[$workout->getStartedAt()->setTimezone($zone)->format('Y-m-d')] = true;
            }
        }
        foreach ($this->em->getRepository(FoodIntake::class)->findBy(['user' => $user]) as $intake) {
            $days[(string) $intake->getDate()?->format('Y-m-d')] = true;
            // The night's fast of the day after starts at this day's last intake (MAG-770).
            $days[(string) $intake->getDate()?->modify('+1 day')->format('Y-m-d')] = true;
        }
        foreach ([DailyActivity::class, HealthDay::class] as $class) {
            foreach ($this->em->getRepository($class)->findBy(['user' => $user]) as $row) {
                $days[$row->getDate()->format('Y-m-d')] = true;
            }
        }
        unset($days['']);
        ksort($days);

        return array_map('strval', array_keys($days));
    }
}
