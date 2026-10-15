<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Repository\MealSettingsRepository;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * The meal settings, one row per user, created on first read: how many guests
 * a meal that names none is cooked for (MAG-482).
 *
 * Read and written through `/api/meal-settings/me`, never through an API
 * Platform operation on an identifier-less path, which would build a fresh
 * entity on write (MeOperationContractTest). Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: MealSettingsRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_meal_settings_user', columns: ['user_id'])]
class MealSettings implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const DEFAULT_GUESTS = 2;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(options: ['default' => self::DEFAULT_GUESTS])]
    private int $defaultGuests = self::DEFAULT_GUESTS;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getUser(): User
    {
        return $this->user;
    }

    public function setUser(User $user): static
    {
        $this->user = $user;

        return $this;
    }

    public function getDefaultGuests(): int
    {
        return $this->defaultGuests;
    }

    /** @throws \InvalidArgumentException when it is below one */
    public function setDefaultGuests(int $defaultGuests): static
    {
        if ($defaultGuests < 1) {
            throw new \InvalidArgumentException(\sprintf('defaultGuests must be a whole number of at least 1, not %d.', $defaultGuests));
        }
        $this->defaultGuests = $defaultGuests;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'defaultGuests' => $this->defaultGuests,
        ], $changedProperties);
    }
}
