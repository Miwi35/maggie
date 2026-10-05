<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Roles are granted from the console only, never from a route: whoever can run
 * it already has a shell in the pod. A token already minted keeps its old
 * roles, so the holder needs a new one.
 */
abstract class AbstractUserRoleCommand extends Command
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly MessageBusInterface $bus,
    ) {
        parent::__construct();
    }

    abstract protected function apply(User $user, string $role): void;

    abstract protected function successMessage(string $email, string $role): string;

    protected function configure(): void
    {
        $this
            ->addArgument('email', InputArgument::REQUIRED, 'Email of the user')
            ->addArgument('role', InputArgument::REQUIRED, 'One of: '.implode(', ', User::GRANTABLE_ROLES));
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $email = (string) $input->getArgument('email');
        $role = (string) $input->getArgument('role');

        if (!in_array($role, User::GRANTABLE_ROLES, true)) {
            $io->error(sprintf('Role "%s" cannot be managed here. Allowed: %s.', $role, implode(', ', User::GRANTABLE_ROLES)));

            return Command::FAILURE;
        }

        $user = $this->userRepository->findOneBy(['email' => $email]);
        if (null === $user) {
            $io->error(sprintf('No user with email "%s".', $email));

            return Command::FAILURE;
        }

        $this->apply($user, $role);
        $this->entityManager->flush();
        // The `users` document carries the roles; a direct flush does not reach the indexing middleware.
        $this->bus->dispatch(new IndexDocumentCommand(User::class, (string) $user->getId()));

        $io->success($this->successMessage($email, $role).' A token minted before is unchanged: issue a new one.');

        return Command::SUCCESS;
    }
}
