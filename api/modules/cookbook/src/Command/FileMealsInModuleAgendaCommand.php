<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Service\ModuleAgendas;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Mercure\MercureTopic;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\TransportNamesStamp;

/**
 * Files every meal in the « Repas » module agenda (MAG-324).
 *
 * Until then a meal went in whatever agenda the client picked, so some sit in the
 * default one — and Google holds a copy of them. Each moves into its user's module
 * agenda (created if need be), loses its Google tracking, and the copy Google holds
 * is deleted: the meal is an internal object and Google never sees it again.
 *
 * Cannot be a SQL migration: removing the copy takes the Google API, and the open
 * screens and the search index have to be told. Safe to run again — once every meal
 * is in its module agenda there is nothing left to do. A deploy runs it right after
 * the migrations.
 */
#[AsCommand(
    name: 'app:cookbook:file-meals-in-module-agenda',
    description: 'Move every meal into its user\'s « Repas » module agenda and remove its Google copy',
)]
final class FileMealsInModuleAgendaCommand extends Command
{
    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly MessageBusInterface $messageBus,
        private readonly HubInterface $hub,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'Report what would move and change nothing');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        /** @var list<Meal> $misfiled */
        $misfiled = $this->entityManager->createQueryBuilder()
            ->select('m', 'a')
            ->from(Meal::class, 'm')
            ->join('m.agenda', 'a')
            ->where('a.module IS NULL OR a.module <> :module')
            ->setParameter('module', Agenda::MODULE_COOKBOOK)
            ->getQuery()
            ->getResult();

        if ([] === $misfiled) {
            $io->success('Every meal is already in its module agenda.');

            return Command::SUCCESS;
        }

        $moved = 0;
        $fromGoogle = 0;
        $createdAgendas = [];
        $googleDeletions = [];
        /** @var array<string, Agenda> $targets */
        $targets = [];

        foreach ($misfiled as $meal) {
            $user = $meal->getAgenda()->getUser();
            $userId = (string) $user->getId();

            if ($dryRun) {
                ++$moved;
                if (null !== $meal->getGoogleEventId()) {
                    ++$fromGoogle;
                }

                continue;
            }

            if (!isset($targets[$userId])) {
                [$targets[$userId], $created] = $this->moduleAgendas->forUser($user, Agenda::MODULE_COOKBOOK, 'Repas', '#FF6B35');
                if ($created) {
                    $createdAgendas[$userId] = $targets[$userId];
                }
            }

            if ($meal->getAgenda() === $targets[$userId]) {
                continue;
            }

            $googleEventId = $meal->getGoogleEventId();
            if (null !== $googleEventId) {
                $googleDeletions[] = new DeleteEventFromGoogleCommand(
                    agendaId: (string) $meal->getAgenda()->getId(),
                    googleEventId: $googleEventId,
                );
                ++$fromGoogle;
            }

            $meal->setAgenda($targets[$userId]);
            $meal->setGoogleEventId(null);
            $meal->setGoogleEtag(null);
            $meal->setGoogleUpdatedAt(null);
            ++$moved;
        }

        if (!$dryRun) {
            $this->entityManager->flush();

            // First, and on their own: the Google ids are gone from the rows now, so a failure
            // further down must not be able to lose them — a re-run finds nothing left to file.
            foreach ($googleDeletions as $deletion) {
                // Queued, not attempted inline: one refusal from Google must not stop the run half-way.
                $this->messageBus->dispatch($deletion, [new TransportNamesStamp(['async'])]);
            }

            // The announcements are best effort: the rows are right, the next reindex fixes the rest.
            try {
                foreach ($createdAgendas as $userId => $agenda) {
                    $this->messageBus->dispatch(new IndexDocumentCommand(entityClass: Agenda::class, entityId: (string) $agenda->getId()));
                    $this->publish(MercureTopic::collection($agenda), (string) $agenda->getId(), $userId, $agenda->toMercurePayload());
                }

                foreach ($misfiled as $meal) {
                    $userId = (string) $meal->getAgenda()->getUser()->getId();
                    $this->messageBus->dispatch(new IndexDocumentCommand(entityClass: Meal::class, entityId: (string) $meal->getId()));
                    $this->publish(MercureTopic::collection($meal), (string) $meal->getId(), $userId, $meal->toMercurePayload());
                }
            } catch (\Throwable $e) {
                $io->warning('Meals are filed, but announcing them failed: '.$e->getMessage().' Run app:elasticsearch:reindex --all.');
            }
        }

        $io->success(sprintf(
            '%s%d meal(s) filed in the module agenda, %d Google copy(ies) %s, %d module agenda(s) created.',
            $dryRun ? '[dry-run] ' : '',
            $moved,
            $fromGoogle,
            $dryRun ? 'to delete' : 'queued for deletion',
            \count($createdAgendas),
        ));

        return Command::SUCCESS;
    }

    /** @param array<string, mixed> $payload */
    private function publish(string $collectionTopic, string $id, string $userId, array $payload): void
    {
        $iri = MercureTopic::item($collectionTopic, $id);
        $this->hub->publish(new Update(
            topics: [MercureTopic::scoped($userId, $iri)],
            data: json_encode(['@id' => $iri] + $payload, JSON_THROW_ON_ERROR),
            private: true,
        ));
    }
}
