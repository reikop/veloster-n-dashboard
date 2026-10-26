"""Project-authored hooks for the exact profile-gated OEM menu only."""
ACTIVITY = 'com/hkmc/system/app/downloadmenu/DownloadMenu.smali'
HELPER = 'Lcom/veloster/menu/HubEntryHelper;'
INSERTIONS = [
    ('    .line 551\n',
     '    invoke-static {p0}, ' + HELPER + '->prepare(Landroid/content/Context;)V\n\n'),
    ('    :cond_62\n',
     '    invoke-static {v2}, ' + HELPER + '->adjustDisplayCount(I)I\n'
     '    move-result v2\n\n'),
    ('    :goto_bf\n',
     '    invoke-static {}, ' + HELPER + '->originalCount()I\n'
     '    move-result v2\n\n'),
    ('    :cond_66\n',
     '    iget-object v0, p0, Lcom/hkmc/system/app/downloadmenu/DownloadMenu;->itemList:Ljava/util/ArrayList;\n'
     '    invoke-static {v0}, ' + HELPER + '->append(Ljava/util/ArrayList;)V\n\n'),
]

def patch_activity(text):
    # Labels can occur in other methods: match within the selected method only.
    for method, indices in [('init()V', [0, 1, 2]), ('addItem()V', [3])]:
        start = text.index('.method private ' + method + '\n')
        end = text.index('.end method', start)
        body = text[start:end]
        for index in indices:
            anchor, insertion = INSERTIONS[index]
            assert body.count(anchor) == 1, (method, anchor)
            if anchor.lstrip().startswith(':'):
                body = body.replace(anchor, anchor + insertion)
            else:
                body = body.replace(anchor, insertion + anchor)
        text = text[:start] + body + text[end:]
    return text

def unpatch_activity(text):
    for _, insertion in INSERTIONS:
        assert text.count(insertion) == 1
        text = text.replace(insertion, '')
    return text
