<?php

namespace Maggie\Core\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\Patch;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Repository\UserRepository;
use Maggie\Core\State\UpdateUserProcessor;
use Maggie\Core\State\UserItemProvider;
use Maggie\Core\Trait\HasGoogleOAuthTokensTrait;
use Symfony\Component\Security\Core\User\UserInterface;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: UserRepository::class)]
#[ORM\Table(name: '"user"')]
#[Indexed(index: 'users', module: 'core')]
#[ApiResource(operations: [
    new Get(requirements: ['id' => '[0-9A-HJKMNP-TV-Z]{26}']),
    new Get(name: 'me', uriTemplate: '/users/me', provider: UserItemProvider::class),
    new Patch(processor: UpdateUserProcessor::class),
])]
class User implements UserInterface, MercurePublishable, IndexableInterface
{
    use HasGoogleOAuthTokensTrait;
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255, unique: true)]
    #[IndexedField(type: 'text', boost: 2.0, keyword: true)]
    private string $email;

    #[ORM\Column(length: 255, unique: true)]
    private string $googleId;

    #[ORM\Column(length: 255)]
    #[IndexedField(type: 'text', boost: 2.0)]
    private string $name;

    #[ORM\Column(length: 512, nullable: true)]
    private ?string $avatar = null;

    /** @var list<string> */
    #[ORM\Column(type: Types::JSON)]
    private array $roles = [];

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getEmail(): string
    {
        return $this->email;
    }

    public function setEmail(string $email): static
    {
        $this->email = $email;

        return $this;
    }

    #[Ignore]
    public function getGoogleId(): string
    {
        return $this->googleId;
    }

    public function setGoogleId(string $googleId): static
    {
        $this->googleId = $googleId;

        return $this;
    }

    public function getName(): string
    {
        return $this->name;
    }

    public function setName(string $name): static
    {
        $this->name = $name;

        return $this;
    }

    public function getAvatar(): ?string
    {
        return $this->avatar;
    }

    public function setAvatar(?string $avatar): static
    {
        $this->avatar = $avatar;

        return $this;
    }

    /** @return list<string> */
    #[Ignore]
    public function getRoles(): array
    {
        $roles = $this->roles;
        $roles[] = 'ROLE_USER';

        return array_values(array_unique($roles));
    }

    /** @param list<string> $roles */
    public function setRoles(array $roles): static
    {
        $this->roles = $roles;

        return $this;
    }

    #[Ignore]
    public function getUserIdentifier(): string
    {
        return $this->email;
    }

    #[Ignore]
    public function eraseCredentials(): void
    {
    }

    // Override trait methods to hide sensitive data from serialization

    #[Ignore]
    public function getGoogleAccessToken(): ?string
    {
        return $this->googleAccessToken;
    }

    #[Ignore]
    public function getGoogleRefreshToken(): ?string
    {
        return $this->googleRefreshToken;
    }

    #[Ignore]
    public function getGoogleTokenExpiresAt(): ?\DateTimeImmutable
    {
        return $this->googleTokenExpiresAt;
    }

    #[Ignore]
    public function hasGoogleCalendarTokens(): bool
    {
        return null !== $this->googleRefreshToken;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'email' => $this->email,
            'name' => $this->name,
            'avatar' => $this->avatar,
            'roles' => $this->getRoles(),
            'googleId' => $this->googleId,
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'email' => $this->email,
            'name' => $this->name,
            'avatar' => $this->avatar,
            'googleTaskListId' => $this->getGoogleTaskListId(),
        ], $changedProperties);
    }
}
