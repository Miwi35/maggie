<?php

declare(strict_types=1);

namespace Maggie\Calendar\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;

/**
 * The task settings, one row per user, created on first read (MAG-406). The
 * alert schedule and the morning time join them with MAG-407.
 *
 * Read and written through `/api/task-settings/me`, never through an API
 * Platform operation on an identifier-less path, which would build a fresh
 * entity on write (MeOperationContractTest). Not searched, so not indexed.
 */
#[ORM\Entity(repositoryClass: TaskSettingsRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_task_settings_user', columns: ['user_id'])]
class TaskSettings implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    /**
     * The weights of the order score: {@see TaskOrderWeights}.
     *
     * @var array<string, array<string, int>>
     */
    #[ORM\Column(type: Types::JSON)]
    private array $orderWeights = TaskOrderWeights::DEFAULTS;

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

    /** @return array<string, array<string, int>> every weight, the defaults filling what is not stored */
    public function getOrderWeights(): array
    {
        return TaskOrderWeights::complete($this->orderWeights);
    }

    /** @param array<string, array<string, int>> $orderWeights */
    public function setOrderWeights(array $orderWeights): static
    {
        $this->orderWeights = $orderWeights;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'orderWeights' => $this->getOrderWeights(),
        ], $changedProperties);
    }
}
