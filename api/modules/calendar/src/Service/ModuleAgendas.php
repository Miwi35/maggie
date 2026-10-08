<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Core\Entity\User;

/**
 * The internal agenda a module keeps for a user, created the first time it is needed (MAG-324).
 *
 * Found by its `module` attribute and never by its name, so the user can rename it
 * without the module losing track of it. It is not synced with Google and is never
 * the default agenda: nothing here sets either. An ordinary agenda that already bears the
 * module's name (and is neither default nor Google's) is taken over instead of duplicated.
 */
class ModuleAgendas
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * @return array{Agenda, bool} the agenda, and whether this call created or took it over
     *                             (persisted, not flushed — the caller announces it once it has flushed)
     */
    public function forUser(User $user, string $module, string $name, ?string $color = null): array
    {
        $agenda = $this->agendaRepository->findOneByModule($user, $module);
        if (null !== $agenda) {
            return [$agenda, false];
        }

        // Meals filed before the attribute existed sit in an ordinary agenda created under
        // the module's name: take it over rather than leave the user with two.
        $legacy = $this->agendaRepository->findOrdinaryNamed($user, $name);
        if (null !== $legacy) {
            $legacy->setModule($module);

            return [$legacy, false];
        }

        $agenda = new Agenda();
        $agenda->setUser($user);
        $agenda->setName($name);
        $agenda->setModule($module);
        $agenda->setColor($color);
        $this->em->persist($agenda);

        return [$agenda, true];
    }
}
