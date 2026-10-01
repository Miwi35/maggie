<?php

namespace Maggie\Calendar\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'maggie:google-calendar:renew-watch',
    description: 'Renew expiring Google Calendar push notification channels (--all: every channel)',
)]
class GoogleCalendarRenewWatchCommand extends Command
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
        private readonly GoogleCalendarApiClient $apiClient,
        private readonly EntityManagerInterface $entityManager,
        private readonly string $googleWebhookUrl,
        private readonly string $googleWebhookToken,
        private readonly string $kernelEnvironment,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('all', null, InputOption::VALUE_NONE, 'Replace every channel, not only the expiring ones');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        // Checked before any channel is touched: with --all, a bad address
        // would otherwise stop every working channel and then fail to create
        // the replacements.
        try {
            GoogleCalendarApiClient::assertUsableWebhookUrl($this->googleWebhookUrl, $this->kernelEnvironment);
        } catch (\InvalidArgumentException $e) {
            $io->error($e->getMessage());

            return Command::FAILURE;
        }

        // --all: the deploy replaces every channel so none keeps an address
        // the configuration no longer holds (MAG-193). Channels only
        // remember their expiry, not where they push, so it cannot tell which.
        $threshold = $input->getOption('all')
            ? new \DateTimeImmutable('+100 years')
            : new \DateTimeImmutable('+1 hour');
        $agendas = $this->agendaRepository->findWithExpiringWatchChannels($threshold);

        if (empty($agendas)) {
            $io->info('No watch channels need renewal.');

            return Command::SUCCESS;
        }

        $renewed = 0;
        foreach ($agendas as $agenda) {
            $user = $agenda->getUser();
            $io->info("Renewing watch for agenda: {$agenda->getName()}");

            // Stopping the old channel has its own catch: an id Google no
            // longer knows — the channel expired, or a connection already
            // closed it — would otherwise throw before the new channel is
            // asked for, and the agenda would never get one again.
            if ($agenda->getGoogleWatchChannelId() && $agenda->getGoogleWatchResourceId()) {
                try {
                    $this->apiClient->stopWatch(
                        $user,
                        $agenda->getGoogleWatchChannelId(),
                        $agenda->getGoogleWatchResourceId(),
                    );
                } catch (\Throwable $e) {
                    $io->warning("Could not stop the previous channel: {$e->getMessage()}");
                }
            }

            try {
                // Create new channel
                $result = $this->apiClient->watchEvents(
                    $user,
                    $agenda->getGoogleCalendarId(),
                    $this->googleWebhookUrl,
                    $this->googleWebhookToken,
                );

                $agenda->setGoogleWatchChannelId($result['channelId']);
                $agenda->setGoogleWatchResourceId($result['resourceId']);
                $agenda->setGoogleWatchExpiresAt(
                    (new \DateTimeImmutable())->setTimestamp((int) ($result['expiration'] / 1000))
                );

                $this->entityManager->flush();
                ++$renewed;
            } catch (\Throwable $e) {
                $io->warning("Failed to renew watch for {$agenda->getName()}: {$e->getMessage()}");
            }
        }

        $io->success("Renewed {$renewed} watch channel(s).");

        // A failed renewal must reach the caller — the deploy warns on it.
        return $renewed < \count($agendas) ? Command::FAILURE : Command::SUCCESS;
    }
}
