git config --global core.autocrlf true

git add -A
git commit -m "update to v6.3.1 based on NekoBoxForAndroid"
git push origin master
git tag -a v6.3.1 -m "v6.3.1 based on NekoBoxForAndroid"
git push origin --tags
pause