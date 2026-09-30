<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Doctrine\ORM\EntityManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Signs the technical account the post-deploy smoke suite acts as (MAG-106).
 *
 * The suite must read production as an authenticated user, and Google sign-in
 * cannot be driven from CI. This is a console command, not a route: whoever can
 * run it already has a shell in the pod, so it opens no door that shell does
 * not — unlike an HTTP login, which would exist on the public host.
 *
 * The account is the suite's own so that the questions it asks Maggie never
 * land in the owner's conversation, and it is created on first use so a fresh
 * cluster needs no manual step. Nothing else is written.
 *
 * It is indexed like any other user: the suite runs `app:elasticsearch:status
 * --check`, which compares row counts, and a `users` index one document short
 * would fail it and roll back a healthy release. A direct flush does not reach
 * the Messenger middleware that indexes, so the command dispatches it itself.
 *
 * stdout carries the token alone, for `$(…)` capture.
 */
#[AsCommand(
    name: 'app:smoke:token',
    description: 'Print a JWT for the technical account used by the post-deploy smoke suite',
)]
final class SmokeTokenCommand extends Command
{
    public const EMAIL = 'smoke@maggieai.fr';
    public const GOOGLE_ID = 'technical-smoke-account';
    public const NAME = 'Smoke test (compte technique)';

    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly JWTTokenManagerInterface $jwtManager,
        private readonly MessageBusInterface $bus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $user = $this->userRepository->findOneBy(['email' => self::EMAIL]);

        if (null === $user) {
            $user = (new User())
                ->setEmail(self::EMAIL)
                ->setGoogleId(self::GOOGLE_ID)
                ->setName(self::NAME);

            $this->entityManager->persist($user);
            $this->entityManager->flush();
        }

        // Idempotent, so it is sent on every run: an earlier message lost to a
        // worker restart is repaired by the next deploy.
        $this->bus->dispatch(new IndexDocumentCommand(User::class, (string) $user->getId()));

        $output->writeln($this->jwtManager->create($user));

        return Command::SUCCESS;
    }
}
