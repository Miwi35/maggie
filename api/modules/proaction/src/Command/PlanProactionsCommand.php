<?php

declare(strict_types=1);

namespace Maggie\Proaction\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Proaction\Service\AgentHubClient;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'maggie:proaction:plan',
    description: 'Ask the agent to plan proactions for the given scope (daily/monthly/yearly)',
)]
class PlanProactionsCommand extends Command
{
    private const PROMPTS = [
        'daily' => <<<'PROMPT'
            Tu es en mode autonome. Analyse le calendrier d'aujourd'hui et les habitudes de l'utilisateur.
            Crée des proactions pertinentes pour la journée en utilisant l'outil schedule_proaction.
            Par exemple : rappels avant les réunions, résumé du matin, préparation de la journée.
            Utilise get_upcoming_events pour voir le planning du jour (1 jour).
            Ne crée pas de proaction si rien de pertinent n'est trouvé.
            PROMPT,
        'monthly' => <<<'PROMPT'
            Tu es en mode autonome. Analyse le calendrier du mois à venir.
            Crée des proactions pour les dates importantes : anniversaires, deadlines, événements récurrents.
            Utilise get_upcoming_events pour voir le planning du mois (30 jours).
            Utilise schedule_proaction pour créer les proactions.
            Ne crée pas de proaction si rien de pertinent n'est trouvé.
            PROMPT,
        'yearly' => <<<'PROMPT'
            Tu es en mode autonome. Réfléchis aux proactions annuelles importantes.
            Crée des proactions pour : voeux de nouvel an, résolutions, bilans trimestriels.
            Utilise schedule_proaction pour créer les proactions.
            Ne crée pas de proaction si rien de pertinent n'est trouvé.
            PROMPT,
    ];

    public function __construct(
        private readonly AgentHubClient $agentHubClient,
        private readonly UserRepository $userRepository,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('scope', 's', InputOption::VALUE_REQUIRED, 'Planning scope: daily, monthly, yearly', 'daily');
        $this->addOption('user', 'u', InputOption::VALUE_OPTIONAL, 'User ID (defaults to first user)');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $scope = $input->getOption('scope');

        if (!isset(self::PROMPTS[$scope])) {
            $io->error(sprintf('Invalid scope "%s". Valid: daily, monthly, yearly', $scope));
            return Command::FAILURE;
        }

        $userId = $input->getOption('user');
        if ($userId === null) {
            $users = $this->userRepository->findAll();
            if (count($users) === 0) {
                $io->warning('No users found.');
                return Command::SUCCESS;
            }

            foreach ($users as $user) {
                $this->planForUser((string) $user->getId(), $scope, $io);
            }
        } else {
            $this->planForUser($userId, $scope, $io);
        }

        return Command::SUCCESS;
    }

    private function planForUser(string $userId, string $scope, SymfonyStyle $io): void
    {
        $io->info(sprintf('Planning %s proactions for user %s...', $scope, $userId));

        try {
            $result = $this->agentHubClient->executeProaction($userId, self::PROMPTS[$scope]);
            $io->success(sprintf('Agent response: %s', mb_substr($result['response'], 0, 200)));

            if (!empty($result['tool_calls'])) {
                $io->writeln(sprintf('  Tool calls: %d', count($result['tool_calls'])));
            }
        } catch (\Throwable $e) {
            $io->error(sprintf('Failed: %s', $e->getMessage()));
        }
    }
}
