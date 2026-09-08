# Historique des sauvegardes en graphe — brouillon initial

> **Brouillon de conception, pas de code.** Le document complet (modèle, stockage, capture, ramassage
> des nœuds fantômes, questions ouvertes, ce que ça implique côté LiteBox **et** côté Argosy) vit dans
> l'autre dépôt : `ExtendDB/LbApiHost/docs/save-history-tree-draft.md`. Ce fichier n'est qu'un pointeur,
> même convention que celle déjà utilisée par `LbApiHost/docs/saves.md` pour référencer un document de
> l'autre côté.
>
> Écrit le 2026-09-08. Chaque point du document doit être relu et confirmé avant toute implémentation —
> traité plus tard, délibérément pas enchaîné dans la foulée des correctifs de save-sync de la même nuit
> (voir §8 du document principal pour ce qui a été livré ce soir-là et qui reste en place).
