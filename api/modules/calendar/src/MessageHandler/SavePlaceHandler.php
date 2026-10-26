<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Exception\InvalidPlaceException;
use Maggie\Calendar\Message\SavePlaceCommand;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Calendar\Service\PlaceInput;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Refusals are {@see \DomainException}s, the caller's to read. A new home takes
 * the flag from the old one in the same flush: one home per user.
 */
#[AsMessageHandler]
class SavePlaceHandler
{
    public function __construct(
        private readonly PlaceRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(SavePlaceCommand $command): Place
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        if (null === $command->placeId) {
            $place = (new Place())->setUser($user)->setCreatedAt($this->clock->now());
            PlaceInput::apply($place, $command->fields);
            $this->em->persist($place);
        } else {
            $place = $this->repository->findOwned($user, $command->placeId)
                ?? throw new InvalidPlaceException('id', "no place of yours has the id {$command->placeId}.");
            PlaceInput::apply($place, $command->fields);
        }

        if ($place->isHome()) {
            foreach ($this->repository->findHomes($user) as $home) {
                if ($home !== $place) {
                    $home->setIsHome(false);
                }
            }
        }

        $this->em->flush();

        return $place;
    }
}
