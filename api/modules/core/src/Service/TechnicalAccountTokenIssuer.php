<?php

declare(strict_types=1);

namespace Maggie\Core\Service;

use Doctrine\ORM\EntityManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Finds or creates a technical account, makes sure it carries the given roles,
 * and signs its JWT. Shared by the smoke and Recette token commands.
 */
final class TechnicalAccountTokenIssuer
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly JWTTokenManagerInterface $jwtManager,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @param list<string> $roles */
    public function issueToken(string $email, string $googleId, string $name, array $roles = []): string
    {
        $user = $this->userRepository->findOneBy(['email' => $email]);

        if (null === $user) {
            $user = (new User())->setEmail($email)->setGoogleId($googleId)->setName($name);
            $this->entityManager->persist($user);
        }

        foreach ($roles as $role) {
            $user->addRole($role);
        }

        $this->entityManager->flush();

        // Idempotent, so it is sent on every run: an earlier message lost to a
        // worker restart is repaired by the next deploy. A direct flush does not
        // reach the Messenger middleware that indexes.
        $this->bus->dispatch(new IndexDocumentCommand(User::class, (string) $user->getId()));

        return $this->jwtManager->create($user);
    }
}
