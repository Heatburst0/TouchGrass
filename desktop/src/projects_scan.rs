//! Read IDE-owned recent lists locally. Only folder names escape this module.
use serde_json::Value;
use std::collections::BTreeSet;
use std::path::Path;

const MAX_PROJECTS: usize = 200;

/// Decode before splitting so encoded separators can never leak a path.
fn folder_name(value: &str) -> Option<String> {
    let path = if let Some((_, rest)) = value.split_once("://") {
        let start = rest.find('/')?;
        rest[start..].split(['?', '#']).next()?
    } else {
        value
    };
    let mut bytes = Vec::with_capacity(path.len());
    let mut input = path.as_bytes().iter().copied();
    while let Some(byte) = input.next() {
        if byte == b'%' {
            let hi = (input.next()? as char).to_digit(16)?;
            let lo = (input.next()? as char).to_digit(16)?;
            bytes.push((hi * 16 + lo) as u8);
        } else {
            bytes.push(byte);
        }
    }
    let decoded = String::from_utf8(bytes).ok()?;
    let name = decoded
        .trim_end_matches(['/', '\\'])
        .rsplit(['/', '\\'])
        .next()?
        .trim();
    let name = if name.to_ascii_lowercase().ends_with(".code-workspace") {
        &name[..name.len() - ".code-workspace".len()]
    } else {
        name
    };
    // Roots, unresolved macros, controls and drive letters are never projects.
    if name.is_empty()
        || matches!(name, "." | "..")
        || name.contains([':', '$', '/', '\\'])
        || name.chars().any(char::is_control)
    {
        return None;
    }
    Some(name.chars().take(80).collect())
}

fn storage_folders(text: &str) -> Vec<String> {
    let Ok(value) = serde_json::from_str::<Value>(text) else {
        return Vec::new();
    };
    let mut paths = Vec::new();
    if let Some(workspaces) = value
        .pointer("/profileAssociations/workspaces")
        .and_then(Value::as_object)
    {
        paths.extend(workspaces.keys().map(String::as_str));
    }
    if let Some(folders) = value
        .pointer("/backupWorkspaces/folders")
        .and_then(Value::as_array)
    {
        paths.extend(
            folders
                .iter()
                .filter_map(|folder| folder.get("folderUri")?.as_str()),
        );
    }
    if let Some(folder) = value
        .pointer("/windowsState/lastActiveWindow/folder")
        .and_then(Value::as_str)
    {
        paths.push(folder);
    }
    if let Some(windows) = value
        .pointer("/windowsState/openedWindows")
        .and_then(Value::as_array)
    {
        paths.extend(
            windows
                .iter()
                .filter_map(|window| window.get("folder")?.as_str()),
        );
    }
    paths
        .into_iter()
        .filter_map(folder_name)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .take(MAX_PROJECTS)
        .collect()
}

fn recent_folders(text: &str) -> Vec<String> {
    let mut names = BTreeSet::new();
    for entry in text.split("<entry").skip(1) {
        // Do not accidentally read attributes on a child element or <entrySomething>.
        if !entry.starts_with(char::is_whitespace) {
            continue;
        }
        let Some((tag, _)) = entry.split_once('>') else {
            continue;
        };
        let Some((_, rest)) = tag.split_once("key=\"") else {
            continue;
        };
        let Some((key, _)) = rest.split_once('"') else {
            continue;
        };
        let key = key
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&");
        // RecentProjectsManager also contains entries keyed by non-path metadata.
        if !key.contains(['/', '\\']) {
            continue;
        }
        if let Some(name) = folder_name(&key) {
            names.insert(name);
        }
    }
    names.into_iter().take(MAX_PROJECTS).collect()
}

pub fn scan() -> Vec<(String, String)> {
    let Some(appdata) = std::env::var_os("APPDATA") else {
        return Vec::new();
    };
    scan_at(Path::new(&appdata))
}

fn scan_at(appdata: &Path) -> Vec<(String, String)> {
    let mut projects = BTreeSet::new();
    for (dir, app) in [
        ("Code", "visual studio code"),
        ("Code - Insiders", "visual studio code - insiders"),
        ("Cursor", "cursor"),
        ("Windsurf", "windsurf"),
    ] {
        if let Ok(text) =
            std::fs::read_to_string(appdata.join(dir).join("User/globalStorage/storage.json"))
        {
            projects.extend(
                storage_folders(&text)
                    .into_iter()
                    .map(|name| (app.to_string(), name)),
            );
        }
    }
    // Union versions so upgrades do not hide projects absent from the new list.
    for vendor in ["JetBrains", "Google"] {
        let Ok(dirs) = std::fs::read_dir(appdata.join(vendor)) else {
            continue;
        };
        for dir in dirs.flatten() {
            let name = dir.file_name().to_string_lossy().to_string();
            let products: &[(&str, &str)] = if vendor == "Google" {
                &[("AndroidStudio", "android studio")]
            } else {
                &[
                    ("IntelliJIdea", "intellij idea"),
                    ("IdeaIC", "intellij idea"),
                    ("PyCharm", "pycharm"),
                    ("WebStorm", "webstorm"),
                    ("CLion", "clion"),
                    ("GoLand", "goland"),
                    ("Rider", "rider"),
                    ("RustRover", "rustrover"),
                    ("PhpStorm", "phpstorm"),
                    ("DataGrip", "datagrip"),
                ]
            };
            let Some((_, app)) = products.iter().find(|(prefix, _)| name.starts_with(prefix))
            else {
                continue;
            };
            if let Ok(text) = std::fs::read_to_string(dir.path().join("options/recentProjects.xml"))
            {
                projects.extend(
                    recent_folders(&text)
                        .into_iter()
                        .map(|name| (app.to_string(), name)),
                );
            }
        }
    }
    projects.into_iter().take(MAX_PROJECTS).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_and_uris_only_reveal_the_final_folder() {
        for (path, expected) in [
            ("file:///c%3A/Users/private/Touch%20Grass/", "Touch Grass"),
            ("vscode-remote://wsl+Ubuntu/home/private/proj", "proj"),
            ("file:///C:/secret/team.code-workspace", "team"),
            ("$USER_HOME$/AndroidStudioProjects/TouchGrass", "TouchGrass"),
            (r"C:\private\TouchGrass\", "TouchGrass"),
            ("file:///C:/private/parent%2Fchild%5Cleaf", "leaf"),
            ("file:///C:/private/caf%C3%A9", "café"),
        ] {
            assert_eq!(folder_name(path).as_deref(), Some(expected), "{path}");
        }
        for path in [
            "",
            "file:///C:/",
            "vscode-remote://private-host",
            "$USER_HOME$/",
            "..",
            "file:///secret/%ZZ",
            "file:///secret/%00",
        ] {
            assert_eq!(folder_name(path), None, "{path}");
        }
        let name = folder_name(&format!("C:/private/{}", "é".repeat(100))).unwrap();
        assert_eq!(name.chars().count(), 80);
        assert!(!name.contains(['/', '\\']));
    }

    #[test]
    fn storage_reads_only_folder_fields_and_deduplicates() {
        let text = r#"{
            "profileAssociations": {"workspaces": {"file:///C:/secret/One": "profile"}},
            "backupWorkspaces": {"folders": [{"folderUri": "file:///C:/secret/Two"}, {"folderUri": "file:///C:/secret/One"}]},
            "windowsState": {"lastActiveWindow": {"folder": "vscode-remote://host/home/Three"}, "openedWindows": [{"folder": "file:///C:/secret/Four.code-workspace"}, {"file": "file:///secret/private.rs"}]},
            "unrelated": "file:///secret/ignore"
        }"#;
        assert_eq!(storage_folders(text), ["Four", "One", "Three", "Two"]);
        assert!(storage_folders("{broken").is_empty());
    }

    #[test]
    fn recent_projects_ignore_metadata_and_decode_xml_attributes() {
        let text = r#"<entry key="$USER_HOME$/Projects/A&amp;B" />
            <entry key="C:\private\Two\" /><entry key="/home/private/A&amp;B" />
            <entry key="lastOpenedProject" value="/secret/ignore" />
            <entry value="other"><option key="/secret/ignore" /></entry>
            <entryOther key="/secret/ignore" /><entry key="/" />"#;
        assert_eq!(recent_folders(text), ["A&B", "Two"]);
    }
}
